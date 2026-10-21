# Testing

| Suite | Command | What it needs | Count (last run) |
|---|---|---|---|
| Backend unit + integration | `./mvnw verify` | Docker (Testcontainers) or `TEST_DATABASE_URL` | 543 |
| Receiver simulator | included in `./mvnw verify` | nothing | 79 |
| Dashboard components | `cd frontend && npm test && npm run typecheck` | Node 22 | 56 |
| API contract and generated types | `OpenApiContractTest` (in `verify`); `npm run gen:api && git diff --exit-code src/api/schema.ts` | | 1 |
| Browser end-to-end (Playwright) | `cd frontend && npm run e2e` | the Compose stack running | 23 |
| Receiver examples | see [below](#receiver-examples) | Java 21, Node 22, Python 3 | 22 / 19 / 19 |
| Compose demos | `scripts/demo.sh <scenario>` | the Compose stack, bash, `jq` | 14 scenarios |
| Local load benchmark | `python3 scripts/bench.py` | the Compose stack, Python 3 | see [below](#local-load-benchmark) |

CI (`.github/workflows/ci.yml`) runs all of them on every push: the backend and frontend jobs, the receiver examples, and a `compose` job that builds the real Docker images, starts them on an empty database, runs the delivery, retry, replay and crash demos, and runs the Playwright suite in a browser.

## Backend

```bash
# Uses Testcontainers (Docker) by default, or an existing PostgreSQL:
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/webhooks_test ./mvnw verify
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
  - audit fault injection: a late result racing the reaper (no deadlock, observation kept), an endpoint deleted while a retry is recorded, an undecryptable secret that neither blocks the queue nor loops, secret rotation and URL edits between claim and send, the circuit opening during an attempt;
  - database outages seen by a real worker instance behind a cuttable TCP proxy: during claim, after the receiver accepted but before success was stored (at-least-once re-send), and while scheduling a retry;
- end-to-end classification over real sockets: every status in the §7.4 table, DNS failure, a dropped connection, a connect timeout, `Retry-After` dates and invalid values, and a `2xx` whose body stalls (delivered, not timed out);
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
- forwarded headers: spoofed `X-Forwarded-For` and `X-Forwarded-Proto` from an untrusted peer are ignored; a trusted proxy's client addresses and HTTPS are honoured;
- dashboard serving: every dashboard route and deep link returns `index.html` with security headers and no caching, hashed assets are cached, unknown paths are denied;
- the OpenAPI contract against `frontend/openapi.json`.

## Browser end-to-end tests

The Playwright suite (`frontend/e2e/`) drives a real browser against a running stack, signing in once through the login page:

1. a wrong password is refused, and nothing is reachable without a session;
2. sign-out ends the session on the server;
3. an endpoint registered in the dashboard receives a producer's event, the simulator verifies the signature as `VALID`, the delivery page shows the attempt, and a full reload of the deep link works;
4. a receiver answering `500` then `503` then `200` is retried until delivered, with one `webhook-id` throughout;
5. a receiver that always answers `503` exhausts the retries, the delivery appears under **Dead letters**, and **Replay** delivers it after the receiver is fixed, leaving the dead letter unchanged;
6. an API key is shown once, works, and is refused after **Revoke**;
7. a rotated secret is shown once, and during the grace period receivers on the old and on the new secret both verify;
8. an endpoint registered with the simulator's base URL gets `HTTP 404` with a hint naming `/receivers/{name}`, is fixed with **Edit**, and the dead letter is replayed and verified.

`e2e/scenarios.spec.ts` adds:

9. unsafe or invalid endpoint URLs (metadata address, localhost, private range, not a URL) are refused in the dashboard;
10. a `503` is retried after the jittered backoff (1–2 s locally) and the attempt history shows it;
11. a `400` is dead-lettered after one attempt and never sent again;
12. exhausted retries show all four attempts;
13. the circuit breaker opens after 10 failures, holds new work without sending it, and drains the backlog once the receiver recovers;
14. work queued while the application restarts is delivered afterwards (needs `E2E_RESTART_CMD`, for example `docker compose restart app`; skipped without it);
15. anonymous callers get `401` on every protected route, and API keys get `403` on management routes;
16. the overview shows delivery metrics and endpoint health;
17. every dashboard route survives a full reload;
18. a captured session cookie is useless after sign-out.

The login limit is 5 a minute per address: wait a minute between full runs against the same stack.

```bash
docker compose up -d --build          # from the repository root
cd frontend
npx playwright install chromium       # once
npm run e2e
```

Environment variables (`E2E_BASE_URL`, `E2E_SIMULATOR_URL`, `E2E_SIMULATOR_FROM_APP`, `E2E_EMAIL`, `E2E_PASSWORD`, `E2E_API_KEY`, `E2E_SIMULATOR_TOKEN`) point the suite at another stack. Each test creates its own endpoint and receiver and deletes them afterwards. Failures keep a trace and a screenshot in `frontend/test-results/`.

## Local load benchmark

`scripts/bench.py` (Python standard library only) creates its own endpoints, simulator receivers and API keys, submits events from several threads, waits until every delivery has finished, prints a JSON summary (ingestion latency and rate, delivery completion latency, outcome counts, peak queue and connection-pool gauges) and removes what it created.

```bash
python3 scripts/bench.py --events 200 --endpoints 4 --concurrency 8 --keys 4            # smoke run
python3 scripts/bench.py --events 2000 --endpoints 8 --concurrency 32 --keys 10
python3 scripts/bench.py --events 1000 --endpoints 8 --concurrency 16 --keys 10 \
    --receiver-delay-ms 50 --failure-rate 0.2
```

`--api`, `--sim`, `--email`, `--password`, `--metrics-token` and `--sim-token` point it at another stack. Ingestion is limited to 50 requests a second per API key, so `--keys` spreads the load. The numbers describe the machine they ran on, with the producer, platform, database and simulator sharing it; they are not a capacity statement. Results from the audit are in [AUDIT-REPORT.md](AUDIT-REPORT.md#performance).

## Receiver examples

```bash
cd examples/receivers/java && javac -d build Receiver.java && java -cp build VerifyTest.java
cd examples/receivers/node && node --test
cd examples/receivers/python && python3 -m unittest
```

All three check the shared [`test-vectors.json`](../examples/receivers/test-vectors.json), which the platform's own signer generated: valid signatures, rotation with two signatures, tampered bodies, wrong secrets, timestamps outside the 5-minute tolerance, missing headers, unknown versions and malformed entries. Each also starts its server and checks `2xx`, `401` and duplicate handling over HTTP.
