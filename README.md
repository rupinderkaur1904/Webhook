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
| 5. Security hardening | Login sessions, API-key management, full SSRF test matrix, rate limits, request limits | **Done** |
| 6. Reliability and observability | Circuit breaker, endpoint health, metrics, structured logging | **Done** |
| 7. Final verification | E2E suite, deployment, complete documentation | Planned |

Deployment packaging (containers, TLS, reverse proxy) arrives in Stage 7; see [Known limitations](#known-limitations-current-stage) before exposing an instance.

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

### Circuit breaker and endpoint health

Each endpoint has a circuit breaker, so a receiver that is down does not use up worker capacity or the retry budget of everything queued for it.

- **Closed:** deliveries flow. A failure is a retryable outcome other than `429` (5xx, timeout, connection, DNS or TLS error, 408/425). Ten consecutive failures open the circuit (`CIRCUIT_FAILURE_THRESHOLD`). A 2xx, or a non-retryable answer such as `400` (the endpoint is reachable; the payload is the problem), ends the streak. `429` and blocked destinations change nothing.
- **Open:** workers do not claim the endpoint's deliveries. They stay `PENDING` or `RETRYING` with **no attempt recorded and no retry budget used**, and are picked up once the circuit closes. The cool-down is 30s, doubling each time the circuit reopens, capped at 30 minutes (`CIRCUIT_BASE_COOLDOWN`, `CIRCUIT_MAX_COOLDOWN`).
- **Half-open:** after the cool-down the maintenance loop picks exactly one probe, the endpoint's earliest due delivery. If it succeeds (or gets a non-retryable answer) the circuit closes and the backlog drains. If it fails, the circuit reopens with a longer cool-down. If the probe ends without deciding (for example it was cancelled, or dead-lettered after repeated `429`s), another probe is picked, so a circuit never stays stuck.
- **Manual reset:** the endpoint page has **Close circuit now** (`POST /endpoints/{id}/circuit/reset`, session only).
- The circuit's counters are updated in the same transaction that records the attempt, so they always agree with the attempt history. A worker's late result after its lease expired is discarded and does not count.

**Health** is shown on the endpoint list, the endpoint page and the overview. Rules are evaluated in order:

| Health | Rule |
|---|---|
| `DISABLED` | The endpoint is disabled. |
| `FAILING` | The circuit is open or half-open, or the last 5 attempts all failed. |
| `DEGRADED` | Attempt success rate under 90% with at least 10 attempts in the window, or any dead letter in the window. |
| `HEALTHY` | At least one attempt in the window and none of the above. |
| `NO_DATA` | No attempts in the window. |

Health also reports attempts, failed attempts, success rate, p50/p95 latency and dead letters for the window, plus the last success and the last failure (outcome, HTTP status, error) at any time.

### Delivery semantics

Delivery is **at-least-once**, not exactly-once. A receiver can see the same `webhook-id` more than once. That happens when it processes a request but the response is lost, when a timeout fires after processing, or later when a delivery is replayed or a worker lease expires. The platform guarantees that an accepted event is durably stored and attempted. Preventing duplicate side effects is the receiver's job, by deduplicating on `webhook-id`.

## API

All routes are under `/api/v1`. Errors are `application/problem+json` with a stable `code` and the `requestId` (also in the `X-Request-Id` response header). Resources owned by another account return `404`.

There are two ways to authenticate:
- **Dashboard session** (people): sign in with email and password. The session lives in PostgreSQL and the browser holds only an HttpOnly cookie. Every state-changing request needs the CSRF token: `GET /auth/csrf` sets the readable `XSRF-TOKEN` cookie, and requests echo it in the `X-XSRF-TOKEN` header.
- **API key** (producers): `Authorization: Bearer whk_…`. A key can submit events and read (`GET`). It cannot manage endpoints, replay, rotate secrets or manage keys; those return `403 SESSION_REQUIRED`. A leaked ingestion key therefore cannot redirect webhooks or mint more keys.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/auth/csrf` | none | Issue the CSRF token. |
| `POST` | `/auth/login` | none + CSRF | `{email, password}`. Starts a session with a new session id. `401 INVALID_CREDENTIALS`, `429` after 5 attempts a minute per address or per email. |
| `POST` | `/auth/logout` | session | Ends the session server-side (`204`). |
| `GET` | `/auth/me` | session | The signed-in user. |
| `POST` | `/auth/register` | none + CSRF | Only when `REGISTRATION_ENABLED=true`, otherwise `404`. Password 12–128 characters. `409 EMAIL_TAKEN`. |
| `GET` / `POST` | `/api-keys` | session | List keys (prefix, created, last used, revoked), or create one: `{name}` → `201 {id, name, key, prefix}`, the key shown **once**. At most 20 active keys. |
| `DELETE` | `/api-keys/{id}` | session | Revoke (`204`); the key stops working immediately. |

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register an endpoint. Returns the signing secret **once** (`201`). At most 50 endpoints per account (`422 ENDPOINT_LIMIT_REACHED`). |
| `POST` | `/endpoints/{id}/rotate-secret` | `{gracePeriodHours: 0..72}` (default 24). Returns the new secret **once**, with `previousSecretExpiresAt`. Until then every delivery carries two signatures, new and old. |
| `GET` | `/endpoints` | List endpoints, each with its `circuit` and 24h `health`. |
| `GET` / `PATCH` / `DELETE` | `/endpoints/{id}` | Read, update (`name`, `description`, `url`, `eventTypes`, `enabled`), or soft-delete. Deleting cancels queued deliveries. |
| `POST` | `/endpoints/{id}/test` | Queue a real `webhook.test` event to this endpoint. |
| `GET` | `/endpoints/{id}/health?window=1h\|24h\|7d` | Endpoint health over the window (default 24h). See [Circuit breaker and endpoint health](#circuit-breaker-and-endpoint-health). |
| `POST` | `/endpoints/{id}/circuit/reset` | Close the endpoint's circuit by hand; queued deliveries resume on the next poll. |
| `POST` | `/events` | Submit an event. `Idempotency-Key` header required. Body at most 256 KB (`413`). 50 per second per credential, bursts of 100 (`429` with `Retry-After`). |
| `GET` | `/events`, `/events/{id}` | List events, with per-status delivery counts. Filter by `eventType`, `from`, `to`; cursor-paginated. Or get one event with its deliveries. |
| `GET` | `/deliveries` | Filter by `status` (repeatable), `endpointId`, `eventId`, `eventType`, `deadLetterReason`, `from` (inclusive), `to` (exclusive). Cursor-paginated. The dead-letter view is `status=DEAD_LETTERED`. |
| `GET` | `/deliveries/{id}` | Delivery with its attempt history, the payload sent, `replayEligibility` and the replay `chain`. |
| `POST` | `/deliveries/{id}/replay` | Replay a dead letter (`202` with the new delivery). See [Dead letters and replay](#dead-letters-and-replay). |
| `GET` | `/metrics/overview?window=1h\|24h\|7d` | Overview numbers for the dashboard (definitions below). |

Endpoint management, replay and rotation need a session; the `GET` routes and `POST /events` accept either.

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
| `endpointHealth` | Every endpoint with its circuit and health over the same window, worst first (failing, degraded, healthy, no data, disabled). |

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

## Security

**Accounts and sessions**
- Passwords are hashed with **Argon2id**. Hashes from before Stage 5 (bcrypt) still verify and are re-hashed with Argon2id at the next login.
- An unknown email is checked against a dummy hash, so the response time and the error (`INVALID_CREDENTIALS`) are the same as for a wrong password.
- Login creates a **new session id** (any earlier session on that browser is invalidated) and a new CSRF token.
- Sessions are stored in PostgreSQL (Spring Session JDBC): they survive restarts and work across instances, and logout deletes them. The cookie `WEBHOOKS_SESSION` is `HttpOnly`, `Secure` and `SameSite=Lax`; it times out after 8 hours idle (`SESSION_TIMEOUT`).
- Self-registration is off unless `REGISTRATION_ENABLED=true`. The first account comes from `BOOTSTRAP_EMAIL` / `BOOTSTRAP_PASSWORD`.

**API keys**
- 32 random bytes; only the SHA-256 hash is stored, and the key is shown once. Keys are created, listed and revoked from the dashboard (or `/api-keys` with a session). `last_used_at` is tracked.

**Signing secrets**
- Encrypted at rest with AES-256-GCM using `SECRET_ENCRYPTION_KEY`; the ciphertext is bound to its endpoint.
- Write-only: a secret appears in a response only when it is created or rotated. Responses show `secretPrefix` so secrets can be told apart.
- **Rotation** keeps the old secret signing alongside the new one for a grace period (0–72 hours), so receivers can switch without rejecting anything.

**SSRF**
- At registration: `https` only, no credentials or fragment, and every resolved address must be public. Loopback, private, link-local and cloud-metadata ranges, documentation ranges, numeric and hex IP forms (`2130706433`, `0x7f.1`) and IPv6 forms that embed them are all blocked.
- At send time the HTTP client's DNS resolver repeats the check and connects only to addresses that passed, closing the DNS-rebinding gap. Redirects are never followed.
- `SSRF_ALLOWED_HOSTS` allowlists exact `host[:port]` entries. It's used for the local simulator and must be empty in production.

**Request limits**
- API request bodies are capped at 256 KB (`413 PAYLOAD_TOO_LARGE`), checked from `Content-Length` and counted while streaming for chunked bodies. JSON nesting is capped at 64 levels.
- Rate limits (Bucket4j, in memory): event ingestion 50/s per credential with bursts of 100; login 5 a minute per client address and per email. Over the limit: `429 RATE_LIMITED` with `Retry-After`.
- At most 50 endpoints and 20 active API keys per account.

**Browser hardening and logging**
- Responses carry a strict `Content-Security-Policy` (`default-src 'self'`, no inline scripts, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: strict-origin-when-cross-origin`. `vite preview` serves the built dashboard with the same headers.
- Log lines carry the request id. No code path logs a secret or key; as a backstop, anything shaped like `whsec_…` or `whk_…` is masked in log output, in both the plain-text and the JSON format.

**Operational endpoints**
- `/actuator/health` is public (liveness and readiness, no details).
- `/actuator/prometheus` needs `Authorization: Bearer $METRICS_TOKEN` (at least 24 characters, compared in constant time). Without `METRICS_TOKEN` it refuses every request. The token opens nothing else, and no other actuator endpoint is exposed.

**Outbound limits:** 5 s connect timeout, plus a hard 15 s deadline for the whole response. Only the first 2 KB of the response body is read and stored, after stripping control characters. Only the `content-type`, `content-length`, `retry-after` and `date` response headers are stored.

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

The `local` profile creates the bootstrap account `dev@localhost` / `local-dev-password`, which signs in to the dashboard, and the API key `whk_local_dev_key_0000000000000000000000` for submitting events. It also allowlists the simulator on `localhost:9090` and allows the session cookie over plain HTTP. Create more API keys on the dashboard's **API keys** page.

Production configuration uses these environment variables:
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- `SECRET_ENCRYPTION_KEY` (base64 of 32 random bytes, for example from `openssl rand -base64 32`)
- `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD`, `BOOTSTRAP_API_KEY`
- `REGISTRATION_ENABLED` (default `false`), `SESSION_TIMEOUT` (default `8h`)
- `SESSION_COOKIE_SECURE` (default `true`; set `false` only for plain-HTTP local development)
- `METRICS_TOKEN` (enables `/actuator/prometheus` for a scraper holding it), `LOG_FORMAT` (empty for plain text, `ecs` or `logstash` for JSON)
- `CIRCUIT_FAILURE_THRESHOLD` (default `10`), `CIRCUIT_BASE_COOLDOWN` (`30s`), `CIRCUIT_MAX_COOLDOWN` (`30m`)

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
scripts/demo.sh breaker       # always 500: circuit opens after 10 failures, 10 more events wait without using retries;
                              # fix the receiver -> one probe closes the circuit and all 20 are DELIVERED
scripts/demo.sh rotation      # rotate with 24h grace -> two signatures; the old and the new secret both verify
scripts/demo.sh security      # 401 anonymous, 403 API key on management, 403 without CSRF, 413 oversized, 429 after bad logins
```

The script signs in as the bootstrap account for management calls (cookie jar plus CSRF token) and submits events with the API key, as a producer would. The session is kept in `$TMPDIR/webhook-demo-cookies.txt` between runs, because logins are limited to 5 a minute. `security` uses up that allowance for your address for a minute.

`crash` kills whatever is listening on the API port and restarts the jar, using the local profile's 15s lease and 2s reaper interval. Set `CRASH_CMD` and `START_CMD` to use something else, for example `docker compose kill app` and `docker compose start app`.

The simulator's request log marks repeated `webhook-id`s as duplicates. This shows why receivers must deduplicate.

The `local` profile shortens the circuit cool-down to 5s so `breaker` finishes in seconds, and sets the scrape token `local-dev-metrics-token-00000000`.

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

## Observability

**Metrics.** `GET /actuator/prometheus` (bearer `METRICS_TOKEN`) exports JVM, HTTP, Hikari and these application metrics, all tagged `application="webhook-platform"`:

| Metric | Meaning |
|---|---|
| `webhooks_events_received_total{result}` | Events accepted, and idempotent replays. |
| `webhooks_deliveries_created_total` | Deliveries created by fan-out. |
| `webhooks_attempts_seconds{outcome}` | Attempt count and duration by outcome (`SUCCESS`, `HTTP_SERVER_ERROR`, `TIMEOUT`…). |
| `webhooks_deliveries_finished_total{status,reason}` | Deliveries that reached `DELIVERED` or `DEAD_LETTERED` (with the reason). |
| `webhooks_deliveries_retries_scheduled_total` | Retries scheduled. |
| `webhooks_attempts_stale_results_total` | Results discarded because the worker had lost its lease. |
| `webhooks_leases_expired_total` | In-flight deliveries recovered by the reaper. |
| `webhooks_circuit_transitions_total{from,to}` | Circuit state changes, including manual resets. |
| `webhooks_queue_deliveries{status}` | Pending, retrying and in-flight deliveries now. |
| `webhooks_queue_due`, `webhooks_queue_oldest_due_seconds` | The due backlog and how long its oldest item has waited: the main alerting signal for a stuck or undersized worker pool. |
| `webhooks_circuits{state}` | Endpoints with an open or half-open circuit. |

Queue and circuit gauges come from PostgreSQL, so every instance reports the same numbers; one query serves all of them and is cached for 5s. If the database is unreachable they report `NaN` rather than failing the scrape.

**Logs.** Plain text by default, with the request id on every line. `LOG_FORMAT=ecs` (or `logstash`) writes one JSON object per line instead. While a delivery is processed, every line carries `deliveryId`, `eventId`, `endpointId`, `accountId` and `attempt`, so one delivery can be followed across retries and workers. Secret-shaped values are masked in both formats.

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
- security:
  - the real login flow through the filter chain: CSRF bootstrap, a session row in PostgreSQL, a new session id on every login, logout revoking the session, the bcrypt-to-Argon2id upgrade, identical answers for a wrong password and an unknown email, registration on and off;
  - who may call what: anonymous `401` on every route, API keys `403` on every management route with nothing changed, CSRF required, a Bearer header that cannot be used to skip CSRF, cross-account `404` on every route that names a resource;
  - API key lifecycle (shown once, hash only, usable, revoked), rotation with both signatures verified, a zero grace period, an expired grace period;
  - secrets and keys absent from all log output, and the log masking backstop;
  - the endpoint cap, a 300 KB body, a chunked body over real HTTP, deeply nested JSON, `requestId` in problem bodies, security headers;
  - rate limits: ingestion `429` with `Retry-After` per key over real HTTP, and the login limit;
- circuit breaker and health:
  - every outcome's signal, every transition and the cool-down cap (unit);
  - end to end on real PostgreSQL: opening after 10 failures, the backlog waiting with its retry budget intact, exactly one probe, a failed probe reopening for twice as long, a successful probe closing and the backlog draining (23 receiver requests for 12 deliveries, none dead-lettered);
  - `429` never opening a circuit, a `400` ending a failure streak, a stuck half-open circuit getting a new probe, manual reset (session only, cross-account `404`);
  - health statistics, the window parameter, `DEGRADED` on a dead letter, and the overview's worst-first list;
- observability: the Prometheus endpoint refusing no token, a wrong token, an API key and a session, and exporting the application metrics with the right token; delivery context on every log line during an attempt; masking in JSON logs;
- dashboard pages with Vitest and Testing Library: overview, deliveries, dead letters (including replay), delivery detail (replay chain, disabled replay with its reason, a rejected replay), events, endpoint detail, sign-in (CSRF header, wrong password, session expiry, sign-out), CSRF retry, API keys (shown once, revoke), secret rotation, endpoint health badges, the circuit explanation and manual reset, the health window, and the overview's endpoint health list.

## Known limitations (current stage)

- **Crash recovery adds latency** of up to `lease-duration + reaper-interval` (75s with the defaults) for work that was in flight when a process died.
- **A paused worker can duplicate a send.** If a worker pauses for longer than its lease (a long GC pause or a frozen VM), it can send at the same moment as the worker that reclaimed the delivery. Fencing keeps the database correct, but the receiver gets the request twice. This is inherent to at-least-once delivery, and why receivers deduplicate on `webhook-id`.
- **Rate limits are per instance.** With N instances a client can reach N times the configured rate.
- **Behind a reverse proxy, login limits see the proxy's address** until forwarded headers are trusted. Stage 7's deployment sets that up; until then every client behind the same proxy shares one login allowance.
- **CSRF uses a double-submit cookie,** the standard pattern for an SPA. It relies on the dashboard being the only site that can set cookies for its domain, so do not serve it next to untrusted subdomains.
- **The dashboard's HTML gets its security headers from whatever serves it.** The backend sends them on API responses and `vite preview` on the built app; the Stage 7 deployment adds them to the production web server.
- **No ordering guarantee** between events.
- **A dead letter stays replayable after a later replay of it succeeded.** Replaying it again sends the event once more. The delivery detail shows the whole chain, so the earlier success is visible before you click.
- **An open circuit's queued rows are still scanned.** The claim query skips them but PostgreSQL reads past them; fine at this scale, a scaling risk for very large backlogs.
- **The circuit opens on a run of consecutive failures,** not on a failure rate. An endpoint failing every other request (like the `intermittent` demo) stays closed and shows as `DEGRADED`.
