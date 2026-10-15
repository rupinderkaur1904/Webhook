"""Run with: python3 -m unittest"""

import base64
import hashlib
import hmac
import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from receiver import make_server, verify

# Signed by the platform's own signer, shared with the Java and Node examples.
VECTORS = json.loads((Path(__file__).parent.parent / "test-vectors.json").read_text("utf-8"))["vectors"]


class VectorTest(unittest.TestCase):
    """One test method per vector (test_vector_<name>), added below."""


def _vector_test(v):
    def test(self):
        valid, reason = verify(v["secret"], v["headers"], v["body"].encode("utf-8"), v["now"])
        self.assertEqual({"valid": valid, "reason": reason}, v["expected"], v["description"])
    return test


for _v in VECTORS:
    setattr(VectorTest, "test_vector_" + _v["name"], _vector_test(_v))


class ServerTest(unittest.TestCase):
    def test_valid_tampered_and_duplicate(self):
        v = next(v for v in VECTORS if v["name"] == "valid")
        secret, body, msg_id = v["secret"], v["body"], v["headers"]["webhook-id"]
        # The vectors use a fixed 'now'; re-sign at the current time so the timestamp check passes.
        timestamp = str(int(time.time()))
        key = base64.b64decode(secret[len("whsec_"):])
        mac = hmac.new(key, f"{msg_id}.{timestamp}.{body}".encode("utf-8"), hashlib.sha256).digest()
        signature = "v1," + base64.b64encode(mac).decode()

        processed = []
        server = make_server(0, secret, on_event=processed.append, log=lambda _: None)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        url = f"http://127.0.0.1:{server.server_address[1]}/webhooks"
        # Bypass any HTTP(S)_PROXY in the environment for this local request.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def post(webhook_id, payload):
            request = urllib.request.Request(url, data=payload.encode("utf-8"), method="POST", headers={
                "Content-Type": "application/json",
                "webhook-id": webhook_id,
                "webhook-timestamp": timestamp,
                "webhook-signature": signature,
            })
            try:
                with opener.open(request) as response:
                    return response.status
            except urllib.error.HTTPError as err:
                return err.code

        self.assertIn(post(msg_id, body), range(200, 300))
        self.assertEqual(post("tampered-" + msg_id, body.replace("42.5", "42.9")), 401)
        self.assertIn(post(msg_id, body), range(200, 300))
        self.assertEqual(len(processed), 1)
        self.assertEqual(processed[0]["type"], "order.created")


if __name__ == "__main__":
    unittest.main()
