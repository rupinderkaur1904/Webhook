# Architecture

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

## Delivery flow

1. `POST /api/v1/events` validates the request and enforces the idempotency key. In **one transaction** it inserts the event and one `PENDING` delivery per target endpoint. It responds `202` only after that commit, and never contacts a destination.
2. The worker claims due deliveries with `SELECT … FOR UPDATE SKIP LOCKED`. Each claim marks the delivery `IN_FLIGHT`, gives it a fresh lease token and expiry, and writes a `STARTED` attempt row. Concurrent workers always receive disjoint sets of deliveries.
3. The request is signed and sent on a virtual thread. **No database transaction is open during the HTTP call.**
4. The result is written in one short transaction. The delivery row is only updated if it still holds this attempt's lease token, so a stale worker cannot overwrite newer state. Its observation is still kept in the attempt history.

## Retries and dead-lettering

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

## What receivers get

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

## Crash recovery

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

## Dead letters and replay

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

## Circuit breaker and endpoint health

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
| `FAILING` | The circuit is open or half-open, or its failure streak has reached 5 (`webhooks.circuit.failing-after`). The streak counts only failures that suggest the receiver is down, as for the circuit: `429`, non-retryable answers such as `400`, and blocked destinations do not count, so an endpoint answering only `429` shows `HEALTHY` until `DEGRADED` applies. |
| `DEGRADED` | Attempt success rate under 90% with at least 10 attempts in the window, or any dead letter in the window. |
| `HEALTHY` | At least one attempt in the window and none of the above. |
| `NO_DATA` | No attempts in the window. |

Health also reports attempts, failed attempts, success rate, p50/p95 latency and dead letters for the window, plus the last success and the last failure (outcome, HTTP status, error) at any time.

## Delivery semantics

Delivery is **at-least-once**, not exactly-once. A receiver can see the same `webhook-id` more than once. That happens when it processes a request but the response is lost, when a timeout fires after processing, or later when a delivery is replayed or a worker lease expires. The platform guarantees that an accepted event is durably stored and attempted. Preventing duplicate side effects is the receiver's job, by deduplicating on `webhook-id`.

## Packaging and runtime

- **One image for the platform.** The `Dockerfile` builds the React dashboard, copies it into the Spring Boot jar, and produces a single image (`--target app`). Spring Boot serves the dashboard's routes (`/overview`, `/endpoints/{id}`, `/deliveries/{id}` and the rest) by forwarding them to `index.html`, so deep links and reloads work. Hashed assets under `/assets/` are cached for a year; `index.html` is never cached. Dashboard routes are public HTML; every API call behind them still needs a session.
- **The simulator is a separate image** (`--target simulator`) and a separate deployment. When it runs publicly, its control API (`PUT /receivers/{name}/config`, the request log, `/verify`) needs `SIMULATOR_CONTROL_TOKEN`; only `POST /receivers/{name}` and `/healthz` are open. Without a token it refuses to start unless `SIMULATOR_OPEN_CONTROL_API=true` (local only, set by Compose). It keeps at most 50 receivers and 200 requests each, in memory.
- **Both images** run as a non-root user on a JRE, with a health check. The platform's JVM uses 75% of the container memory and exits on `OutOfMemoryError` so the orchestrator restarts it.
- **Shutdown:** on `SIGTERM` the worker stops claiming, gives in-flight requests 20 s to finish and record their results, and leaves anything still running to the lease reaper of the next instance.
- **Reverse proxies:** the app trusts `X-Forwarded-For` and `X-Forwarded-Proto` only from the CIDR ranges in `TRUSTED_PROXIES` (see [SECURITY.md](SECURITY.md#reverse-proxies-and-client-addresses)).

```
                 Docker Compose (local)                         Fly.io + Neon (public demo)
  browser ──> app :8080 (Spring Boot + dashboard)      browser ──> Fly proxy (TLS) ──> app machine
                 │            │                                                     │        │
                 ▼            ▼                                                     ▼        ▼
          db (PostgreSQL)  simulator :9090                              Neon PostgreSQL   simulator app
```

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
