# Webhook Delivery & Reliability Platform

A developer-facing platform that durably accepts events and delivers them to registered HTTP endpoints. It signs every request, records every attempt, and makes failures visible.

The full design is in [docs/PLAN.md](docs/PLAN.md). This README describes what is **implemented today**.

## Status

| Stage | Scope | State |
|---|---|---|
| 1. End-to-end foundation | Schema, endpoints, idempotent ingestion, PostgreSQL queue + worker, signing, simulator, minimal dashboard | **Done** |
| 2. Delivery reliability | Retry classification, exponential backoff with jitter, `Retry-After`, retry exhaustion | **Done** |
| 3. Developer experience | Full dashboard, dead-letter view, replay, scripted failure simulator | **Done** |
| 4. Recovery and concurrency | Lease reaper, crash recovery, stale-worker and duplicate scenarios | **Done** |
| 5. Security hardening | Login sessions, API-key management, full SSRF test matrix, rate limits, request limits | Next |
| 6. Reliability and observability | Circuit breaker, endpoint health, metrics, structured logging | Planned |
| 7. Final verification | E2E suite, deployment, complete documentation | Planned |

Until Stage 5, the platform is meant for **local use only**, with a single bootstrap API key.

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

### Retries and dead-lettering

Each failed attempt is classified first.

| Outcome | Retried? |
|---|---|
| 2xx | Success: `DELIVERED` |
| 408, 425, 429, 5xx except 501 and 505 | Yes |
| Timeouts, refused or reset connections, DNS failures, TLS errors | Yes |
| 3xx (redirects are never followed), other 4xx, 501, 505, invalid responses | No: `DEAD_LETTERED` / `NON_RETRYABLE_RESPONSE` |
| Destination resolves to a blocked address | No: `DEAD_LETTERED` / `DESTINATION_BLOCKED` |

A retryable failure with attempts left becomes `RETRYING`, and its `next_attempt_at` is stored in PostgreSQL. No in-memory timers are used, so a restarted instance simply picks the delivery up when it is due. The delay works like this:
- Raw delay: `min(max-delay, base-delay × 2^(n−1))` after attempt *n*.
- Equal jitter: a random value between half the raw delay and the full raw delay. This spreads retries without ever dropping below half the intended backoff.
- On 429 and 503, a valid `Retry-After` (seconds or HTTP-date) raises the delay to at least the requested value, capped at `max-retry-after`. `Retry-After` never adds attempts.

When a retryable failure happens on the last attempt, the delivery becomes `DEAD_LETTERED` / `RETRIES_EXHAUSTED`. Every attempt is kept with its outcome, status code, latency, retryability and `Retry-After`.

Retry settings live under `webhooks.retry.*`:

| Setting | Default | `local` profile |
|---|---|---|
| `max-attempts` | 8 | 4 |
| `base-delay` | 30s | 2s |
| `max-delay` | 1h | 20s |
| `max-retry-after` | 6h | 60s |

With the defaults, retries come after about 30s, 1m, 2m, 4m, 8m, 16m and 32m (before jitter). The budget is copied onto each delivery when it is created, so changing the configuration never changes work that is already queued.

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

### Crash recovery

A worker claims a delivery with a **lease**: a random `lease_token` and an expiry (`webhooks.worker.lease-duration`, 60s by default). It also writes a `STARTED` attempt row before it sends anything. The result is written only if the delivery still carries that token.

Three situations stop a result from being recorded: the process crashes, hangs, or is still sending when graceful shutdown times out. In each case the lease expires and the **lease reaper** recovers the delivery. Every instance runs a reaper every `reaper-interval` (15s). It works through expired leases in batches of `reaper-batch-size` (100), using `SKIP LOCKED`, so several instances never recover the same row. For each expired lease the reaper:
- marks the open attempt `ABANDONED` with outcome `LEASE_EXPIRED`;
- moves the delivery to `RETRYING` with the normal backoff;
- if that was the last attempt, dead-letters it with `RETRIES_EXHAUSTED`;
- if the endpoint was deleted meanwhile, marks it `CANCELLED`.

Abandoned attempts count against the budget. A payload that crashes the worker every time therefore dead-letters instead of looping.

**Stale workers are fenced out.** A worker that was only paused, and reports after its lease was reaped, finds the token changed, so its result does not touch the delivery. What it saw is still stored on its attempt, which stays `ABANDONED` and is flagged `reportedAfterLeaseExpiry`. The history stays honest, and the dashboard marks that attempt "late result, not applied".

**Graceful shutdown:** the worker stops claiming and waits up to `shutdown-timeout` (20s) for in-flight sends to finish and be recorded. Anything still running is left to the reaper after restart.

The app refuses to start if `lease-duration` is not at least `connect-timeout + response-timeout + 5s`. A shorter lease would let a delivery be retried while its first request is still running.

### Dead letters and replay

A dead-lettered delivery keeps its event body, every attempt, the last error and status code, and its reason (`RETRIES_EXHAUSTED`, `NON_RETRYABLE_RESPONSE` or `DESTINATION_BLOCKED`). Nothing retries it automatically.

`POST /api/v1/deliveries/{id}/replay`, or the **Replay** button in the dashboard, creates a **new** delivery with these properties:
- It sends the same event to the same endpoint, at the endpoint's current URL and with its current secret.
- It has a fresh attempt budget and goes through the normal retry policy.
- It is linked to the dead letter by `replayOfDeliveryId`. Every delivery in a chain shares the `rootDeliveryId` of the original.
- The receiver sees the same `webhook-id` (the event id) and a new `X-Webhook-Delivery-Id`.

The dead letter itself and its attempts are never modified.

A replay is refused with `409 REPLAY_NOT_ELIGIBLE` and one of these `reason` values:

| `reason` | When |
|---|---|
| `NOT_DEAD_LETTERED` | The delivery is not dead-lettered. |
| `ENDPOINT_DELETED` / `ENDPOINT_DISABLED` | The endpoint can't receive deliveries. Enable it first. |
| `REPLAY_IN_PROGRESS` | A delivery in the same chain is still pending, retrying or in flight. |
| `REPLAY_LIMIT_REACHED` | The chain already has `webhooks.replay.max-per-delivery` replays (default 10). |

Two simultaneous replay clicks cannot both succeed. A partial unique index allows only one unfinished delivery per chain, and the request that loses gets `REPLAY_IN_PROGRESS`. The replay also takes a share lock on the endpoint row, so deleting the endpoint at the same moment either rejects the replay or cancels it. `GET /deliveries/{id}` reports `replayEligibility` and the full `chain`.

### Delivery semantics

Delivery is **at-least-once**, not exactly-once. A receiver can see the same `webhook-id` more than once. That happens when it processes a request but the response is lost, when a timeout fires after processing, or later when a delivery is replayed or a worker lease expires. The platform guarantees that an accepted event is durably stored and attempted. Preventing duplicate side effects is the receiver's job, by deduplicating on `webhook-id`.

## API

All routes are under `/api/v1` and need `Authorization: Bearer whk_…`. Errors are `application/problem+json` with a stable `code`. Resources owned by another account return `404`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register an endpoint. Returns the signing secret **once** (`201`). |
| `GET` | `/endpoints` | List endpoints. |
| `GET` / `PATCH` / `DELETE` | `/endpoints/{id}` | Read, update (`name`, `description`, `url`, `eventTypes`, `enabled`), or soft-delete. Deleting cancels queued deliveries. |
| `POST` | `/endpoints/{id}/test` | Queue a real `webhook.test` event to this endpoint. |
| `POST` | `/events` | Submit an event. `Idempotency-Key` header required. |
| `GET` | `/events`, `/events/{id}` | List events, with per-status delivery counts. Filter by `eventType`, `from`, `to`; cursor-paginated. Or get one event with its deliveries. |
| `GET` | `/deliveries` | Filter by `status` (repeatable), `endpointId`, `eventId`, `eventType`, `deadLetterReason`, `from` (inclusive), `to` (exclusive). Cursor-paginated. The dead-letter view is `status=DEAD_LETTERED`. |
| `GET` | `/deliveries/{id}` | Delivery with its attempt history, the payload sent, `replayEligibility` and the replay `chain`. |
| `POST` | `/deliveries/{id}/replay` | Replay a dead letter (`202` with the new delivery). See [Dead letters and replay](#dead-letters-and-replay). |
| `GET` | `/metrics/overview?window=1h\|24h\|7d` | Overview numbers for the dashboard (definitions below). |

**Overview metrics.** All of these are computed from the database, using the database clock.

| Field | Definition |
|---|---|
| `eventsSubmitted` | Events created in the window. |
| `deliveriesSucceeded` | Deliveries whose `delivered_at` falls in the window. |
| `deliveriesDeadLettered` | Deliveries whose `dead_lettered_at` falls in the window, as a total and per reason. |
| `deliverySuccessRate` | `succeeded / (succeeded + deadLettered)` over the window. It is `null` when both are 0. |
| `current` | Pending, retrying and in-flight counts right now. These are not windowed. |
| `attempts` | Attempts started in the window that have finished: the succeeded count, the failed count (abandoned attempts count as failed), the success rate, and p50/p95 latency. |
| `recentActivity` | The 20 most recent attempts, including ones still in flight. |

Endpoint health and the circuit breaker are added to the overview in Stage 6.

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

With the backend (`local` profile) and simulator running:

```bash
scripts/demo.sh success       # delivered first time; simulator reports signature VALID
scripts/demo.sh recover       # script [500, 500] then 200 -> DELIVERED on attempt 3
scripts/demo.sh rate-limit    # script [429 + Retry-After: 5] then 200 -> second attempt waits 5s
scripts/demo.sh timeout       # script [200 after 8s] -> attempt 1 TIMEOUT (5s), retry DELIVERED; same webhook-id twice
scripts/demo.sh exhaust       # always 503 -> 4 attempts -> DEAD_LETTERED / RETRIES_EXHAUSTED, listed as a dead letter
scripts/demo.sh permanent     # 400 -> DEAD_LETTERED / NON_RETRYABLE_RESPONSE after one attempt
scripts/demo.sh intermittent  # 50% seeded failures, 8 events -> every one retried until DELIVERED
scripts/demo.sh replay        # exhaust, fix the receiver, replay -> DELIVERED; a second replay -> 409 REPLAY_IN_PROGRESS
scripts/demo.sh idempotency   # same key twice -> 200 replay; same key, different payload -> 409
scripts/demo.sh signature     # verify a delivered request: VALID; tampered body or wrong secret: INVALID_SIGNATURE
scripts/demo.sh crash         # kill -9 the backend mid-request, restart it -> attempt 1 ABANDONED / LEASE_EXPIRED, attempt 2 DELIVERED
```

`crash` kills whatever is listening on the API port and restarts the jar, using the local profile's 15s lease and 2s reaper interval. Set `CRASH_CMD` and `START_CMD` to use something else, for example `docker compose kill app` and `docker compose start app`.

The simulator's request log marks repeated `webhook-id`s as duplicates. This shows why receivers must deduplicate.

### Failure simulator

The simulator runs as its own app on port 9090. `PUT /receivers/{name}/config` accepts the following fields:

```json
{
  "status": 200, "delayMs": 0, "headers": {}, "secret": "whsec_…",
  "script": [ { "status": 500 }, { "status": 429, "headers": { "Retry-After": "5" } }, { "status": 200, "delayMs": 8000 } ],
  "failureRate": 0.3, "failureStatus": 503, "seed": 42
}
```

For each request, the first rule that applies decides the response:
1. The next unused `script` step.
2. With probability `failureRate`, the `failureStatus`. These failures are drawn from a generator seeded with `seed`, so a run can be repeated exactly.
3. The default `status`, `delayMs` and `headers`.

Saving a new config restarts both the script and the random sequence. Setting a delay longer than the platform's response timeout simulates a timeout.

Other endpoints:
- `GET /receivers/{name}/requests` returns the request log. Each entry shows the signature result, whether its `webhook-id` was a duplicate, the status returned and the rule that chose it.
- `POST /verify` checks a signature by hand, for example to show that a tampered body is rejected.

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
- the retry policy (delay bounds for every attempt, the cap, jitter spread, `Retry-After`) and the full classification table;
- retry flows on real PostgreSQL: recovery after failures, exhaustion, 429 and 503 `Retry-After`, permanent 4xx, cancellation when the endpoint is deleted mid-attempt;
- a real stop-and-restart test, where one application instance schedules a retry and a fresh instance completes it;
- recording of 5xx, timeouts, refused connections and redirects (redirects are never followed);
- stale-lease fencing, and 8 workers claiming 400 deliveries concurrently with no duplicates;
- the full worker flow end to end;
- replay:
  - a linked new delivery with the same `webhook-id`;
  - the dead letter left untouched;
  - every eligibility reason;
  - the per-chain limit;
  - 12 concurrent replay requests creating exactly one delivery;
  - cross-account 404;
- overview metrics against seeded deliveries inside and outside the window, plus delivery and event filters;
- simulator scripts, seeded intermittent failures and the verify endpoint;
- crash recovery:
  - a **real crash**: the platform runs as a separate JVM, is killed with SIGKILL mid-request, and the delivery is recovered and delivered;
  - abandoned attempts that count towards dead-lettering;
  - deleting the endpoint during a crash;
  - stale workers fenced out after recovery, with their late observation kept;
  - 4 reapers recovering 250 expired leases exactly once;
  - a crashed replay recovered within its own chain;
  - graceful shutdown, both draining and past its deadline;
- dashboard pages with Vitest and Testing Library: overview, deliveries, dead letters (including replay), delivery detail (replay chain, disabled replay with its reason, a rejected replay), events, and endpoint detail.

## Known limitations (current stage)

- **Crash recovery adds latency** of up to `lease-duration + reaper-interval` (75s with the defaults) for work that was in flight when a process died.
- **A paused worker can duplicate a send.** If a worker pauses for longer than its lease (a long GC pause or a frozen VM), it can send at the same moment as the worker that reclaimed the delivery. Fencing keeps the database correct, but the receiver gets the request twice. This is inherent to at-least-once delivery, and why receivers deduplicate on `webhook-id`.
- **API-key authentication only** (Stage 5). The dashboard keeps the key in `sessionStorage`. Stage 5 replaces this with HttpOnly session cookies.
- **No ordering guarantee** between events.
- **A dead letter stays replayable after a later replay of it succeeded.** Replaying it again sends the event once more. The delivery detail shows the whole chain, so the earlier success is visible before you click.
- **Endpoint health and the circuit breaker** are not built yet (Stage 6).
