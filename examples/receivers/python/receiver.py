"""Minimal Standard Webhooks receiver using only the Python standard library.

    WEBHOOK_SECRET=whsec_... PORT=3000 python3 receiver.py
"""

import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOLERANCE_SECONDS = 5 * 60
MAX_BODY_BYTES = 1024 * 1024
_DIGITS = re.compile(r"[0-9]{1,15}")


def _header(headers, name):
    # http.server's headers are already case-insensitive; a plain dict may not be.
    for key, value in headers.items():
        if key.lower() == name:
            return value
    return None


def _secret_key(secret):
    encoded = secret[len("whsec_"):] if secret.startswith("whsec_") else secret
    return base64.b64decode(encoded, validate=True)


def verify(secret, headers, raw_body, now=None):
    """Verify a delivery. Returns (valid, reason).

    raw_body must be the exact bytes received, never json.dumps(json.loads(body)).
    now is unix seconds (defaults to the current time).
    """
    if now is None:
        now = time.time()
    msg_id = _header(headers, "webhook-id")
    timestamp = _header(headers, "webhook-timestamp")
    signatures = _header(headers, "webhook-signature")
    if msg_id is None or timestamp is None or signatures is None:
        return False, "missing_header"
    if not _DIGITS.fullmatch(timestamp):
        return False, "invalid_timestamp"
    # Reject in both directions: an old timestamp is a replay, a future one a skewed or forged clock.
    if abs(int(now) - int(timestamp)) > TOLERANCE_SECONDS:
        return False, "timestamp_out_of_tolerance"
    signed = f"{msg_id}.{timestamp}.".encode("utf-8") + raw_body
    expected = hmac.new(_secret_key(secret), signed, hashlib.sha256).digest()
    # Several signatures are sent during a secret rotation; any one matching is enough.
    for entry in signatures.strip().split():
        if not entry.startswith("v1,"):
            continue  # unknown version: ignore
        try:
            provided = base64.b64decode(entry[3:], validate=True)
        except (binascii.Error, ValueError):
            continue  # malformed: skip it, another entry may still match
        if hmac.compare_digest(provided, expected):  # constant time
            return True, "ok"
    return False, "no_matching_signature"


def make_server(port, secret, on_event=lambda event: None, log=print):
    """Create (not start) the server. on_event(dict) runs once per webhook-id."""
    _secret_key(secret)  # fail at startup, not per request, if the secret is not base64

    # Deliveries are at-least-once: the same webhook-id arrives again after a timeout, a retry or a
    # replay. This in-memory set forgets everything on restart and is not shared between instances;
    # production needs a durable store (e.g. a unique key in your database, written in the same
    # transaction as the side effect, or Redis SET NX with an expiry longer than the retry window).
    seen = set()
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def _reply(self, status, text):
            body = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path.split("?", 1)[0] != "/webhooks":
                return self._reply(404, "not found")
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES:
                return self._reply(413, "payload too large")
            raw_body = self.rfile.read(length)  # keep the raw bytes: verify these, parse afterwards

            valid, reason = verify(secret, self.headers, raw_body)
            if not valid:
                log(f"rejected delivery: {reason}")
                return self._reply(401, "invalid signature")

            webhook_id = self.headers["webhook-id"]
            with lock:
                duplicate = webhook_id in seen
                seen.add(webhook_id)
            if duplicate:
                log(f"duplicate {webhook_id}, already processed")
                return self._reply(200, "duplicate")
            try:
                payload = json.loads(raw_body)
                # Keep this fast: the platform times out slow receivers and retries. Real work belongs
                # on a queue; acknowledge once the event is safely recorded.
                log(f"received {payload.get('type')} {webhook_id}")
                on_event({"webhook_id": webhook_id, "type": payload.get("type"), "payload": payload})
            except Exception as exc:  # noqa: BLE001 - any failure means "not handled"
                with lock:
                    seen.discard(webhook_id)  # let the platform's retry through
                log(f"processing failed: {exc}")
                return self._reply(500, "error")
            self._reply(200, "ok")

        def do_GET(self):
            self._reply(405, "method not allowed")

        def log_message(self, format, *args):  # silence the default per-request access log
            pass

    return ThreadingHTTPServer(("", port), Handler)


if __name__ == "__main__":
    webhook_secret = os.environ.get("WEBHOOK_SECRET")
    if not webhook_secret:
        sys.exit("Set WEBHOOK_SECRET to the endpoint's signing secret (whsec_...).")
    listen_port = int(os.environ.get("PORT", "3000"))
    server = make_server(listen_port, webhook_secret)
    print(f"listening on http://localhost:{listen_port}/webhooks", flush=True)
    server.serve_forever()
