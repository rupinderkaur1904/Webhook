# Webhook Delivery & Reliability Platform

A developer-facing platform that durably accepts events and delivers them to registered HTTP endpoints. It signs every request, records every attempt, and makes failures visible.

The full design is in [docs/PLAN.md](docs/PLAN.md). This README describes what is **implemented today**.

## Status

| Stage | Scope | State |
|---|---|---|
| 1. End-to-end foundation | Schema, endpoints, idempotent ingestion, PostgreSQL queue + worker, signing, simulator, minimal dashboard | **Done** |
| 2. Delivery reliability | Retry classification, exponential backoff with jitter, `Retry-After`, retry exhaustion | Next |
| 3. Developer experience | Full dashboard, dead-letter view, replay, scripted failure simulator | Planned |
| 4. Recovery and concurrency | Lease reaper, crash recovery, stale-worker and duplicate scenarios | Planned |
| 5. Security hardening | Login sessions, API-key management, full SSRF test matrix, rate limits, request limits | Planned |
| 6. Reliability and observability | Circuit breaker, endpoint health, metrics, structured logging | Planned |
| 7. Final verification | E2E suite, deployment, complete documentation | Planned |

Until Stage 2, **every delivery has a budget of one attempt**. A failed attempt dead-letters the delivery with reason `RETRIES_EXHAUSTED`. Until Stage 5, the platform is meant for **local use only**, with a single bootstrap API key.

## Architecture

```
Producer --API key--> Spring Boot API --(one transaction)--> PostgreSQL (events, deliveries, attempts)
                                                                  ^
                     Delivery worker: claim (FOR UPDATE SKIP LOCKED + lease) | sign | send | record
                                                                  |
                                                                  v
                                          Receiver endpoints / receiver-simulator
```

- `backend/` is the Spring Boot application. It contains the REST API, the delivery worker and the persistence layer, and uses Flyway migrations.
- `receiver-simulator/` is a separate Spring Boot app that acts as a controllable receiver. It verifies signatures independently and logs every request, including duplicate `webhook-id`s.
- `frontend/` is the React + TypeScript dashboard (Vite, Mantine, TanStack Query).

### Delivery flow

1. `POST /api/v1/events` validates the request and enforces the idempotency key. In **one transaction** it inserts the event and one `PENDING` delivery per target endpoint. It responds `202` only after that commit, and never contacts a destination.
2. The worker claims due deliveries with `SELECT … FOR UPDATE SKIP LOCKED`. Each claim marks the delivery `IN_FLIGHT`, gives it a fresh lease token and expiry, and writes a `STARTED` attempt row. Concurrent workers always receive disjoint sets of deliveries.
3. The request is signed and sent on a virtual thread. **No database transaction is open during the HTTP call.**
4. The result is written in one short transaction. The delivery row is only updated if it still holds this attempt's lease token, so a stale worker cannot overwrite newer state. Its observation is still kept in the attempt history.

### What receivers get

```
POST <endpoint url>
Content-Type: application/json
webhook-id: <event id>            stable across retries and replays: deduplicate on this
webhook-timestamp: <unix seconds>
webhook-signature: v1,<base64 HMAC-SHA256>
X-Webhook-Delivery-Id: <delivery id>
X-Webhook-Attempt: <n>

{"id":"…","type":"order.created","createdAt":"…","data":{…}}
```

Signatures follow [Standard Webhooks](https://www.standardwebhooks.com/). The signed bytes are `"{webhook-id}.{webhook-timestamp}." + body`. The HMAC key is the base64-decoded part of the secret after `whsec_`. Receivers should:
- compare signatures in constant time;
- reject timestamps more than 5 minutes old;
- deduplicate on `webhook-id`.

`receiver-simulator/src/main/java/dev/webhooks/simulator/SignatureVerifier.java` is a reference verifier.

### Delivery semantics

Delivery is **at-least-once**, not exactly-once. A receiver can see the same `webhook-id` more than once. That happens when it processes a request but the response is lost, when a timeout fires after processing, or later when a delivery is replayed or a worker lease expires. The platform guarantees that an accepted event is durably stored and attempted. Preventing duplicate side effects is the receiver's job, by deduplicating on `webhook-id`.

## API (Stage 1)

All routes are under `/api/v1` and need `Authorization: Bearer whk_…`. Errors are `application/problem+json` with a stable `code`. Resources owned by another account return `404`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register an endpoint. Returns the signing secret **once** (`201`). |
| `GET` | `/endpoints` | List endpoints. |
| `GET` / `PATCH` / `DELETE` | `/endpoints/{id}` | Read, update (`name`, `description`, `url`, `eventTypes`, `enabled`), or soft-delete. Deleting cancels queued deliveries. |
| `POST` | `/endpoints/{id}/test` | Queue a real `webhook.test` event to this endpoint. |
| `POST` | `/events` | Submit an event. `Idempotency-Key` header required. |
| `GET` | `/events`, `/events/{id}` | List events (cursor-paginated), or get one with its deliveries. |
| `GET` | `/deliveries` | Filter by `status` (repeatable), `endpointId`, `eventId`, `eventType`. Cursor-paginated. |
| `GET` | `/deliveries/{id}` | Delivery with its full attempt history and the payload sent. |

The OpenAPI document is at `/v3/api-docs` and Swagger UI is at `/swagger-ui.html`.

**Event submission:**

```json
POST /api/v1/events
Idempotency-Key: order-123

{ "eventType": "order.created", "payload": { "orderId": "123" }, "endpointIds": ["…"], "occurredAt": "2026-10-15T04:00:00Z" }
```

- `endpointIds` and `occurredAt` are optional.
- Without `endpointIds`, the event fans out to every enabled endpoint whose `eventTypes` filter matches: exact (`order.created`), prefix (`order.*`), or empty (all events).
- Repeating the same request with the same key returns `200` with `Idempotent-Replayed: true` and the original response. JSON key order and whitespace don't matter.
- Reusing a key with a different request returns `409 IDEMPOTENCY_KEY_REUSED`.
- Keys are scoped per account.

## Security in Stage 1

- **API keys** are 32 random bytes. Only their SHA-256 hash is stored.
- **Signing secrets** are encrypted at rest with AES-256-GCM using `SECRET_ENCRYPTION_KEY`, and the ciphertext is bound to its endpoint. A secret is returned only when its endpoint is created.
- **SSRF checks at registration:** the URL must be `https`, with no credentials or fragment, and every resolved address must be public. Loopback, private, link-local and cloud-metadata ranges, documentation ranges, and IPv6 forms that embed them are all blocked.
- **SSRF checks at send time:** the HTTP client's DNS resolver repeats the address check, and the client connects only to the addresses that passed. This closes the DNS-rebinding gap. Redirects are never followed.
- `SSRF_ALLOWED_HOSTS` allowlists exact `host[:port]` entries. It's used for the local simulator and must be empty in production.
- **Outbound limits:** 5 s connect timeout, plus a hard 15 s deadline for the whole response. Only the first 2 KB of the response body is read and stored, after stripping control characters. Only the `content-type`, `content-length`, `retry-after` and `date` response headers are stored.
- **Not yet implemented** (Stage 5): dashboard login, API-key management endpoints, request-size and rate limits, and secret rotation. Do not expose Stage 1 to untrusted users.

## Running locally

Prerequisites: Java 21, Node 22, and PostgreSQL 16 (or Docker).

```bash
docker compose up -d db          # or use an existing PostgreSQL with a 'webhooks' database/user

./mvnw -pl backend -am package -DskipTests
SPRING_PROFILES_ACTIVE=local java -jar backend/target/webhook-platform-0.1.0-SNAPSHOT.jar   # :8080

./mvnw -pl receiver-simulator package -DskipTests
java -jar receiver-simulator/target/receiver-simulator-0.1.0-SNAPSHOT.jar                   # :9090

cd frontend && npm ci && npm run dev                                                        # :5173
```

The `local` profile creates a bootstrap account and the API key `whk_local_dev_key_0000000000000000000000`. It also allowlists the simulator on `localhost:9090`. Paste that key into the dashboard.

Production configuration uses these environment variables:
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- `SECRET_ENCRYPTION_KEY` (base64 of 32 random bytes, for example from `openssl rand -base64 32`)
- `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD`, `BOOTSTRAP_API_KEY`

The app refuses to start without a valid encryption key.

### Demo

With the backend and simulator running:

```bash
scripts/demo.sh success   # submit -> persist -> deliver -> record -> inspect; simulator reports signature VALID
scripts/demo.sh failure   # receiver answers 500 -> attempt recorded, delivery dead-lettered (Stage 1: one attempt)
```

Both scripts also show the idempotency contract: the same key and body return `200`, and the same key with a different body returns `409`.

## Testing

```bash
# Backend: uses Testcontainers (Docker) by default, or an existing PostgreSQL:
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/webhooks_test ./mvnw verify

cd frontend && npm test && npm run typecheck
```

Integration tests always run against real PostgreSQL. They cover:
- migrations from an empty database and schema check constraints;
- ingestion validation, idempotent replay and conflicts, and concurrent duplicate submissions;
- fan-out filters;
- endpoint ownership;
- SSRF rejection at registration and at send time;
- signed delivery, verified with an independent HMAC implementation;
- recording of 5xx, timeouts, refused connections and redirects (redirects are never followed);
- stale-lease fencing, and 8 workers claiming 400 deliveries concurrently with no duplicates;
- the full worker flow end to end.

## Known limitations (current stage)

- **No retries yet** (Stage 2). A failure dead-letters the delivery after one attempt.
- **No lease reaper yet** (Stage 4). If the process dies mid-attempt, that delivery stays `IN_FLIGHT`. Its lease and `STARTED` attempt row are already recorded, so the Stage 4 reaper will recover it.
- **API-key authentication only** (Stage 5). The dashboard keeps the key in `sessionStorage`. Stage 5 replaces this with HttpOnly session cookies.
- **No ordering guarantee** between events.
