# Python webhook receiver

A Standard Webhooks receiver for this platform. It uses only the standard library. `receiver.py` uses `http.server`, `hmac` and `hashlib`; no `pip install` needed.

## Run it

```bash
WEBHOOK_SECRET=whsec_... PORT=3000 python3 receiver.py
```

- `WEBHOOK_SECRET` (required): the endpoint's signing secret (`whsec_…`).
- `PORT` (optional): the port to listen on. Default `3000`.

Deliveries go to `POST /webhooks`. The receiver answers `401` when verification fails. It answers `200` for a new event and also for a duplicate. It logs the event type.

The verification function is separate from the server, so you can reuse it in your own framework:

```
from receiver import verify
verify(secret, headers, raw_body_bytes, now_seconds)
# -> (valid, reason), reason one of: ok, missing_header, invalid_timestamp,
#    timestamp_out_of_tolerance, no_matching_signature
```

## Point the platform at it

**With Docker Compose** (the setup in [docs/LOCAL-TESTING.md](../../../docs/LOCAL-TESTING.md)):

1. In the dashboard, open **Endpoints**, click **Register endpoint**, and use the URL `http://host.docker.internal:3000/webhooks`. The platform runs inside Docker, and `host.docker.internal` is how it reaches your computer. Compose already allows this address. Copy the signing secret it shows once.
2. Start the receiver with that secret, from this folder:
   ```bash
   WEBHOOK_SECRET=whsec_... PORT=3000 python3 receiver.py
   ```
   In Windows PowerShell: `$env:WEBHOOK_SECRET="whsec_..."; python3 receiver.py`
3. On the endpoint's page, click **Send test event**. The receiver logs `received webhook.test …`, and the delivery shows **DELIVERED**.

**Backend started from the command line** (see [docs/SETUP.md](../../../docs/SETUP.md)): register `http://localhost:3000/webhooks` instead, and add the receiver to the allowlist. The setting replaces the `local` profile's list, so keep the simulator in it:

```bash
SPRING_PROFILES_ACTIVE=local WEBHOOKS_SSRF_ALLOWED_HOSTS=localhost:3000,localhost:9090 \
  java -jar backend/target/webhook-platform-0.1.0-SNAPSHOT.jar
```

**In production,** register the receiver's public `https://…/webhooks` URL. Private and local addresses are always refused there.

## Why each check matters

- **All three headers are required.** If any is missing, the request cannot be authenticated, so it is rejected and never processed.
- **The timestamp must be within 5 minutes of now, in either direction.** The timestamp is signed. Without this check, anyone who captured one genuine request could replay it forever. A timestamp far in the future points to a forged request or a badly wrong clock. Keep the receiver's clock in sync with NTP.
- **The HMAC is computed over the raw body bytes.** The signature covers the exact bytes that were sent. If the body is parsed and serialized again, key order, whitespace, number formatting and Unicode escapes can change, and a genuine request fails. In Flask use `request.get_data()`; in Django `request.body`; in FastAPI `await request.body()`.
- **The comparison runs in constant time.** An ordinary `==` stops at the first byte that differs. By timing responses, an attacker can then guess a valid signature byte by byte.
- **Every `v1,` entry is checked.** During a secret rotation, the platform sends two signatures, one with the new secret and one with the old. Accepting a match on any entry lets you switch secrets without dropping deliveries.
- **Unknown versions and malformed base64 are skipped, not treated as errors.** A future signature scheme or one bad entry must not cause a valid `v1` signature in the same header to be rejected.
- **Deduplication on `webhook-id`.** Delivery is at-least-once. Timeouts, retries and manual replays all resend the same `webhook-id`. A duplicate gets a `2xx` so the platform stops retrying, but it is not processed twice. The in-memory set here is only for demonstration. Production needs a durable store shared by all instances, such as a unique key written in the same transaction as the side effect.
- **Return `2xx` quickly.** The platform times out slow responses and retries them. Do heavy work after acknowledging, for example by putting it on a queue.

## Tests

```bash
python3 -m unittest
```

The tests check `verify` against every vector in [`../test-vectors.json`](../test-vectors.json). The platform's own signer (`WebhookSigner`) generated those vectors, and the Java, Node.js and Python examples all share them. A further test starts the server on a random port. It checks that a valid request gets `2xx`, a tampered request gets `401`, and a repeated `webhook-id` is acknowledged but processed only once.
