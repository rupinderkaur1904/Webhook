# Engineering audit and test campaign

Date: 2026-10-16. Branch `stage-7-release` (PR #7). Statuses are PASS, FAIL, BLOCKED, SKIPPED, NOT IMPLEMENTED or NOT VERIFIED, and every PASS below comes from a run whose output was read. "FAIL → fixed" means the test failed on the audited code, the defect was fixed, and the same test now passes.

## 1. Executive summary

- The audit started from commit `7a86074` with a clean tree. All existing suites passed there: 308 backend tests, 21 simulator tests, 36 dashboard tests, typecheck and production build.
- The audit added **293 tests** (235 backend, 58 simulator) plus **10 browser scenarios**. They found **18 defects**, all fixed with a regression test that failed before the fix:
  - 1 **P1**: API-key requests created server-side sessions whose cookie kept working after the key was revoked;
  - 1 **P1**: two concurrent PATCHes of one endpoint could silently lose one change;
  - 8 **P2** and 8 **P3** (section 7).
- After the fixes, on merge commit `d06b1dc`, everything passes:
  - `./mvnw -B clean verify` passes: 543 backend and 79 simulator tests, exit 0;
  - frontend typecheck, 36 tests and production build pass, and the generated API types match the contract;
  - all **18 browser tests** pass against freshly built images, including the restart scenario.
- The local benchmark delivered 2,000 events to 8 endpoints with no losses. Delivery throughput on this shared 4-CPU machine was about 56 deliveries a second, and completion latency grew to p95 27 s under that burst (section 5.7). That figure describes this machine only.
- **This is not a production-readiness sign-off.** Open items:
  - known P3 risks (section 8);
  - Java dependency freshness could not be checked because Maven Central was unreachable;
  - several dashboard behaviours (C12) were not verified;
  - the hosted deployment has never been run.
- Delivery is **at least once**, not exactly once. A receiver can get the same `webhook-id` more than once:
  - when a worker crashes after the receiver accepted the request but before the result was saved;
  - when a lease expires while a slow request is still running.

  Receivers must deduplicate on `webhook-id` (section 8).
- **Stage 5 and Stage 6.** Both are implemented and their acceptance criteria now pass, but PRs #5 and #6 are still **open drafts**. Both P1 defects date from Stage 5, and the fixes exist only on the Stage 7 branch (section 4.3).

## 2. Branch and commits tested

| What | Commit |
|---|---|
| Baseline (before any audit change) | `7a86074` "Fix issues from the first local Windows run" |
| Browser scenarios and benchmark script | `fd10434` |
| CI: Playwright annotations, restart scenario in CI | `ce97282` |
| Merged audit fixes (all final runs) | `d06b1dc` (merges of four audit branches) |
| Benchmark gauge parsing fix | the commit after `d06b1dc` |

The stacked branches: `stage-5-security` is contained in `stage-6-observability`, which is contained in `stage-7-release` (checked with `git merge-base --is-ancestor`).

## 3. Environment and commands

**Environment.** A Linux cloud container with 4 CPUs and 15 GB RAM.
- Java 21 (Maven wrapper), Node 22, Python 3.
- Docker 29.8 with Compose v2.
- Chromium 1194 for Playwright.
- PostgreSQL 16:
  - Testcontainers for the backend suites;
  - `postgres:16-alpine` in Compose.

**Isolation.**
- **Your local stack.** Project `webhook`, ports 5432/8080/9090, volume `webhook_pgdata`. It was never reset or recreated. One mistake did reach it, described in section 9.
- **Browser and benchmark runs.** These used a separate Compose project, `whci`, on ports 5441/8091/9091 with its own volume `whci_pgdata`.
- **Images for the final runs.** Built from `d06b1dc` and tagged `webhook-platform:audit` and `webhook-simulator:audit`, so your `:local` images were not replaced.

**Commands, results and durations**

| Run | Command | Exit | Counts | Time |
|---|---|---|---|---|
| Baseline backend + simulator | `./mvnw -B clean verify` | 0 | 308 + 21, 0 failures | 1 m 45 s |
| Baseline frontend | `npm run typecheck`, `npm test`, `npm run build` | 0, 0, 0 | 36 tests in 8 files | 14 s, 22 s, 10 s |
| Baseline contract | `npm run gen:api && git diff --exit-code src/api/schema.ts` | 0 | no diff | |
| Baseline lint | none configured | | | NOT IMPLEMENTED |
| Final backend + simulator | `./mvnw -B clean verify` | 0 | **543 + 79**, 0 failures, 0 errors, 0 skipped | 3 m 07 s |
| Final frontend | typecheck, `npm test -- --run`, build, `gen:api` diff | 0, 0, 0, 0 | 36 tests, no diff | 24 s total |
| Final browser | `npx playwright test` against the audit images, with `E2E_RESTART_CMD` set | 0 | **18 passed**, 0 skipped | 1 m 40 s |
| CI on `ce97282` | GitHub Actions: backend, frontend, receiver-examples, compose | all success | compose job ran 18 browser tests including restart | |

**Exact browser command for the isolated stack**

```bash
export DB_PORT=5441 APP_PORT=8091 SIMULATOR_PORT=9091
docker compose -p whci -f docker-compose.yml -f whci-override.yml up -d --no-build
cd frontend
E2E_BASE_URL=http://localhost:8091 E2E_SIMULATOR_URL=http://localhost:9091 \
E2E_RESTART_CMD="docker compose -p whci -f ../docker-compose.yml -f whci-override.yml restart app" \
npx playwright test
```

## 4. Feature-by-feature verification

The tables summarise the four focused audits. Each row names the evidence. Unless stated otherwise, test classes are under `backend/src/test/java/dev/webhooks/platform/`.

### 4.1 Functional and reliability (C1–C13)

| Area | Requirement | Evidence | Status |
|---|---|---|---|
| C1 Ingestion | Valid event persisted before `202` | EventIngestionTest | PASS |
| C1 | Malformed JSON → 400, invalid fields → 422, size limit → 413 | EventIngestionTest, AuditIngestionContractTest, LiveServerLimitsTest | PASS |
| C1 | 404/405/415 use the documented problem shape with `code` and `requestId` | AuditIngestionContractTest | FAIL → fixed |
| C1 | Idempotency: same body → 200 replay, different body → 409, concurrent same key → one event | EventIngestionTest, `concurrentSameKeyWithDifferentBodiesCreatesExactlyOneEvent` | PASS |
| C1 | A rejected request does not consume the idempotency key | `aFailedRequestDoesNotBurnTheIdempotencyKey` | PASS |
| C1 | Database failure halfway through fan-out leaves nothing behind | `aDatabaseFailureMidFanOutLeavesNothingBehind` | PASS |
| C1 | Fan-out (exact, `prefix.*`, disabled, deleted endpoints) | EventIngestionTest, AuditIngestionContractTest | PASS |
| C1 | OpenAPI contract matches the code | OpenApiContractTest | FAIL → partly fixed (2 errors fixed; status codes other than 200 still undocumented) |
| C2 Worker | `SKIP LOCKED` claims are disjoint across 8 workers | ClaimConcurrencyTest | PASS |
| C2 | Stale worker cannot overwrite a newer result (fencing) | LeaseRecoveryTest, DeliveryProcessingTest | PASS |
| C2 | Late result racing the lease reaper | `lateWorkerResultRacingTheReaperNeitherDeadlocksNorLosesTheObservation` | FAIL → fixed (deadlock) |
| C2 | Endpoint deleted while a retry is being recorded | `retryRecordedWhileTheEndpointIsBeingDeletedDoesNotStayQueuedForever` | FAIL → fixed |
| C2 | Poison data (undecryptable secret) does not block the queue | `undecryptableSecretCannotBlockTheQueue` | PASS (reported as lease expiry; see section 8) |
| C3 Retry | Status table: 408/425/429/5xx retryable; 400/401/403/404/409/410/422/501/505 permanent, over real HTTP | AuditRetryClassificationTest (18 codes) | PASS |
| C3 | DNS failure, refused, connect timeout, read timeout, dropped connection all retryable | AuditRetryClassificationTest, DeliveryProcessingTest | PASS |
| C3 | Backoff bounds, cap, jitter, max attempts | RetryPolicyTest; browser scenario 10 (observed 1–2 s gap) | PASS |
| C3 | `Retry-After` in seconds, all three HTTP-date forms, invalid, huge | AuditRetryAfterTest, RetryAfterTest | FAIL → fixed (two date forms and non-ASCII digits) |
| C3 | 2xx with a stalling or huge body counts as success | `successfulResponseWithAStallingBodyIsDeliveredNotTimedOut` | FAIL → fixed |
| C4 Replay | Linked new delivery, fresh budget, original untouched, concurrent replays create one | ReplayTest (7 tests); browser scenarios 5 and 8 | PASS |
| C4 | Replay after correcting the URL goes to the new URL | `replayAfterTheUrlWasCorrected…`; browser scenario 8 | PASS |
| C5 Endpoints | Partial PATCH, validation and SSRF on update, atomic failure | AuditEndpointLifecycleTest (28 tests) | PASS |
| C5 | Two concurrent PATCHes of different fields | `concurrentPatchesOfDifferentFieldsDoNotLoseUpdates` | FAIL → fixed |
| C5 | Whitespace-only name returns 422 (was 500) | `whitespaceOnlyNameIsAValidationErrorNotAServerError` | FAIL → fixed |
| C5 | Secrets encrypted at rest (AES-GCM bound to the endpoint), shown only on create and rotate | AuditEndpointLifecycleTest, CredentialsAndSecretsTest | PASS |
| C5 | Rotation overlap: two signatures, retired exactly at expiry | AuditEndpointLifecycleTest; browser test "rotated secret" | PASS |
| C6 Signing | Sent bytes = signed bytes = stored bytes, Unicode included | AuditSigningContractTest (10 payloads) | PASS |
| C6 | Real delivery client verified by the real simulator verifier; any tampering rejected | AuditSigningContractTest | PASS |
| C6 | Shared `test-vectors.json` agrees with the signer, simulator and the three example receivers | AuditSigningContractTest; CI receiver-examples job | PASS |
| C7 Circuit | Opens exactly at 10 failures; one probe with 8 concurrent claimers; recovery drains the backlog | AuditCircuitHealthTest, CircuitBreakerFlowTest; browser scenario 13 | PASS |
| C7 | Health figures (empty data, windows, percentiles) agree across list, detail and overview | AuditCircuitHealthTest | PASS |
| C7 | FAILING rule matches its documentation | `failingFollowsTheCircuitCounterNotRawAttemptFailures` | FAIL → docs fixed (code matches PLAN §7.5) |
| C8 Database | Migrate from empty; upgrade from V1–V4 with data | MigrationTest, AuditMigrationUpgradeTest | PASS |
| C8 | 32 constraints each reject invalid data and accept a valid twin | AuditSchemaConstraintsTest | PASS |
| C8 | Hot queries use indexes at 50k deliveries / 75k attempts | AuditQueryPlanTest (26 paths) | FAIL → fixed (events list 111 ms → 0.28 ms) |
| C8 | Database outage returns `503 DATABASE_UNAVAILABLE` and recovers | AuditDatabaseOutageTest (observability) | FAIL → fixed (was 401) |
| C8 | Worker survives database loss during claim, completion and retry scheduling | AuditDatabaseOutageTest (delivery) | PASS |
| C8 | Rollback on failure in replay and processing | code review only | NOT VERIFIED |
| C9 | See 4.2 | | |
| C10 Simulator | Refuses to start without a token unless the override is set | AuditSimulatorStartupTest (4 real contexts) | PASS |
| C10 | Wrong, missing or malformed token → 401 on every control route; 30 path tricks never reach a control handler | AuditSimulatorAuthTest (54 tests) | PASS |
| C10 | Malformed signing secret returns 400 (was 500) | `malformedSecretsAreRejectedNotServerErrors` | FAIL → fixed |
| C11 Observability | JSON logs, delivery ids in MDC, no secret in logs | AuditOperationsProcessTest (real JVM, `LOG_FORMAT=ecs`) | PASS |
| C11 | `requestId` inside JSON log lines | no request-scoped line occurred | NOT VERIFIED |
| C11 | Prometheus counters change by the exact expected amounts; bounded labels | AuditOperationsProcessTest | PASS |
| C11 | A scrape during a database outage tries the database once (was 7 times) | AuditQueueMetricsOutageTest | FAIL → fixed |
| C11 | Startup with invalid settings exits with a message naming the setting | AuditStartupConfigTest (9 JVMs) | PASS |
| C12 Frontend | Every route survives a reload; logout; anonymous redirect | browser scenarios 16–17; auth.spec | PASS |
| C12 | Endpoint create/edit validation, backend errors shown | browser scenarios 8–9; EndpointEdit.test.tsx | PASS |
| C12 | No secrets in the browser console or local storage | code review (no `console.*`, no storage of secrets) | PASS (review) |
| C12 | Double submit prevented | buttons disabled while loading (code review) | PASS (review) |
| C12 | Replay asks for confirmation | no confirmation dialog | NOT IMPLEMENTED (P3) |
| C12 | Filters in combination; pagination; long URLs and large ids in layouts; expired-session handling in the UI | | NOT VERIFIED |
| C12 | Lint | no ESLint configuration | NOT IMPLEMENTED (P3) |
| C13 Compose | Clean start migrates and creates the admin; health checks | CI compose job on an empty database; isolated `whci` stack | PASS |
| C13 | Data persists across container recreation and daemon restart | your `webhook_pgdata` volume: 72 deliveries since 2026-10-15 survived a Docker daemon crash and restart (read-only count) | PASS |
| C13 | Image contains the dashboard; non-root; exec-form entrypoint | jar inspected (`static/index.html`, `assets/*`); Dockerfile review | PASS |
| C13 | Shutdown and restart do not lose queued work | browser scenario 14; GracefulShutdownTest; WorkerCrashProcessTest; CI crash demo | PASS |
| C13 | Reproducible builds | base images and actions not pinned by digest; Maven wrapper without a checksum | FAIL (P3, not fixed) |
| C13 | Production defaults: secure cookie, registration off, no SSRF allowlist, metrics closed without a token | AuditStartupConfigTest, review | PASS |

### 4.2 Security (C9)

| Requirement | Evidence | Status |
|---|---|---|
| Every non-public route needs authentication (routes discovered from Spring MVC, not a hand-written list) | AuditHttpSecurityTest | PASS |
| API keys can only ingest and read | `apiKeysCanOnlyIngestAndRead`; browser scenario 15 | PASS |
| **API keys never create a server-side session** | AuditApiKeySessionTest, AuditOperationsProcessTest | **FAIL → fixed (P1)** |
| CSRF on all 13 state-changing session routes; a Bearer header cannot skip it | AuditHttpSecurityTest, AccessControlTest | PASS |
| Cross-account isolation on every resource | AccessControlTest, AuditHttpSecurityTest | PASS |
| Session cookie flags, 8 h idle timeout, logout invalidates the session, fixation | SessionAuthTest; browser scenario 18 | PASS |
| Identical responses for wrong password and unknown email | `wrongPasswordAndUnknownEmailBodiesAreIdentical` | PASS |
| Identical timing for those two cases | | NOT VERIFIED |
| SQL injection (all JdbcClient SQL reviewed; values are bound) | review + probe | PASS |
| Out-of-range date filters return 400, not 500 | AuditHttpSecurityTest | FAIL → fixed |
| Firewall rejections return 400, not a misleading 401 | AuditLiveHttpTest | FAIL → fixed |
| SSRF matrix (~55 URL spellings, send-time resolver, redirects) | AuditSsrfMatrixTest (80), DestinationPolicyTest | FAIL → fixed (IPv4-translated IPv6 `::ffff:0:a.b.c.d`) |
| Real DNS rebinding with a live resolver | covered only through the send-time resolver | NOT VERIFIED |
| Security headers on API, errors, assets, SPA; no permissive CORS | AuditHttpSecurityTest | PASS |
| Only `/actuator/health` and the token-protected `/actuator/prometheus` are exposed | `noActuatorEndpointOtherThanHealthIsReachable` | PASS |
| Production dependencies (npm) | `npm audit --omit=dev`: 0 vulnerabilities | PASS |
| Dev dependencies (npm) | `npm audit`: 2 critical, 1 moderate, all via vitest 3.2.7 (fix needs vitest 5) | FAIL (dev only, not shipped; not upgraded) |
| Java dependency freshness and CVEs | Maven Central blocked by the network | BLOCKED |

### 4.3 Stage 5 and Stage 6 against the plan

The pull requests are **#5 (Stage 5, security)** and **#6 (Stage 6, circuit breaker and observability)**. On 2026-10-16 both were open drafts with nothing merged. Their code is fully contained in `stage-7-release`, so every run in this report exercised it.

| Plan milestone and acceptance criterion | Evidence | Status |
|---|---|---|
| **Stage 5 (PLAN M4):** cross-account access impossible across all routes | AccessControlTest, AuditHttpSecurityTest (all 21 routes) | PASS |
| SSRF test matrix passes | AuditSsrfMatrixTest | PASS after fix F-SSRF |
| No secret in any response or log | CredentialsAndSecretsTest, `secretsAppearOnlyInCreateAndRotateResponses`, AuditOperationsProcessTest | PASS |
| Sessions, CSRF, API keys, Argon2id, rate limits, body limits, rotation | SessionAuthTest, LiveServerLimitsTest and others | PASS |
| **Stage 6 (PLAN M5):** 10 failures open the circuit, queued deliveries wait, a probe closes it, the backlog drains | browser scenario 13, CircuitBreakerFlowTest, AuditCircuitHealthTest | PASS |
| No retry budget spent while open | `circuitOpeningDuringAnAttempt…`, CircuitBreakerFlowTest | PASS |
| Health and metrics from real data with documented definitions | AuditCircuitHealthTest, AuditOperationsProcessTest | PASS after two doc corrections |

Two defects came from Stage 5: the P1 session leak and the IPv4-translated SSRF gap. Stage 6 had three: the outage scrape retries, the FAILING rule documentation and the metric name documentation. All five are fixed **only on `stage-7-release`**. If #5 or #6 were merged on their own, those defects would be merged with them. Merging the stack in order (#5, #6, #7) brings the fixes in with #7.

## 5. Results by test type

### 5.1 Unit and integration

On `d06b1dc`, the backend has 543 tests and the simulator 79, with 0 failures. That is 235 backend and 58 simulator tests more than the baseline. Each audit area's tests are listed with their classes in section 7.

### 5.2 Frontend

- Typecheck, 36 component tests, production build and the generated-types diff all pass.
- Lint is NOT IMPLEMENTED: the repository has no ESLint configuration.

### 5.3 Browser

The final run had 18 tests, and all 18 passed. They cover the 16 scenarios you asked for:

| # | Your scenario | Test | Status |
|---|---|---|---|
| 1 | Valid sign-in; invalid credentials refused | global setup; `auth.spec` "a wrong password is refused" | PASS |
| 2 | Register an endpoint with a valid URL | `delivery.spec` "registered in the dashboard…" | PASS |
| 3 | Reject an unsafe or invalid URL | `scenarios.spec` "unsafe or invalid endpoint URLs are refused" | PASS |
| 4 | Send an event; successful delivery | `delivery.spec` | PASS |
| 5 | HMAC verified at the receiver | `delivery.spec` (simulator verdict `VALID`) | PASS |
| 6 | Transient failure retried at the expected time | `scenarios.spec` (gap 950–3000 ms asserted) | PASS |
| 7 | Permanent failure dead-lettered | `scenarios.spec` (one attempt, never resent) | PASS |
| 8 | Exhaust retries; inspect history | `scenarios.spec` (attempts 1–4 shown) | PASS |
| 9 | Correct the endpoint and replay | `delivery.spec` "a URL without a receiver path…" | PASS |
| 10 | Replay succeeds; original intact | `delivery.spec` dead-letter replay | PASS |
| 11 | Secret rotation overlap | `secrets.spec` | PASS |
| 12 | Circuit breaker pause, probe and recovery | `scenarios.spec` | PASS |
| 13 | Restart with pending work | `scenarios.spec` (`E2E_RESTART_CMD`) | PASS |
| 14 | Authentication and authorisation on protected routes | `scenarios.spec` | PASS |
| 15 | Health, metrics and error messages | `scenarios.spec` overview; 404 hint in `delivery.spec` | PASS |
| 16 | Logged-out session cannot be reused | `scenarios.spec` (captured cookie replayed → 401) | PASS |

Failures keep a trace and a screenshot. No run failed in the final pass, so none were produced.

### 5.4 Security

See section 4.2.

### 5.5 Concurrency and fault injection (Phase E)

| Scenario | Expected transitions | Observed | Status |
|---|---|---|---|
| Several workers on one queue | Each due delivery is claimed by exactly one worker (PENDING → IN_FLIGHT) | 8 workers, 400 deliveries, disjoint claims | PASS |
| Worker crash after claim | Lease expires; the reaper moves IN_FLIGHT → RETRYING; the attempt becomes ABANDONED | WorkerCrashProcessTest (SIGKILL), LeaseRecoveryTest | PASS |
| Worker paused past its lease | The newer claim wins; the old worker's result is fenced out by `lease_token` | LeaseRecoveryTest stale tests | PASS; the receiver can get two requests |
| Stale worker updating a newer delivery | The update matches no row; the observation is kept as `reported_after_lease_expiry` | DeliveryProcessingTest, reaper race test | PASS after the deadlock fix |
| Crash after the receiver accepted, before success was saved | Reaper retries; the receiver gets the same `webhook-id` again | AuditDatabaseOutageTest | PASS (at-least-once, as designed) |
| Concurrent duplicate ingestion | One event; the rest get 200 replay or 409 | AuditIngestionContractTest | PASS |
| Database lost during claim, completion and retry scheduling | Errors logged; the loop survives; nothing marked DELIVERED unless saved | AuditDatabaseOutageTest (TCP proxy cut) | PASS |
| Circuit changes during an active delivery | Result recorded; circuit stays OPEN | AuditWorkerFaultsTest | PASS |
| Concurrent edit, rotation and delivery | One consistent secret set per request; edits not lost | AuditWorkerFaultsTest, AuditEndpointLifecycleTest | PASS after the PATCH lock fix |
| Concurrent replay requests | One replay; the others get `REPLAY_IN_PROGRESS` | ReplayTest | PASS |

### 5.6 CI

The compose job failed once on `fd10434`. The Playwright step failed, but the job logs and the report artifact could not be downloaded, because this environment's network blocks GitHub's log storage. The same suite passed twice on a clean isolated stack that replayed CI's exact sequence. The next CI run (`ce97282`) passed all four jobs, including the restart scenario.

- **Cause:** the cause of that one failure is unknown.
- **Change:** CI now reports Playwright failures as check annotations, which can be read through the API.

### 5.7 Performance

Measured with `scripts/bench.py` against the isolated `whci` stack running the audit images.

- **Shared machine:** the producer, platform, PostgreSQL and simulator all ran on one 4-CPU / 15 GB host.
- **Platform settings:** local profile, worker batch 32, max concurrency 32, Hikari pool 20.
- **Limits:** these numbers describe this machine and are **not** a capacity claim.

| Run | Workload | Ingestion | Delivery | Queue and pool |
|---|---|---|---|---|
| Smoke | 200 events, 4 endpoints, 8 producer threads, 4 keys, instant receiver | 200 × `202`; 202 events/s; p50 25 ms, p95 121 ms, p99 230 ms | 200 DELIVERED, 1 attempt each; completion p50 1.6 s, p95 2.4 s; all done 4.6 s after the first submit | oldest due 0.7 s; pool peak 13 of 20 active, 0 waiting |
| Burst | 2,000 events, 8 endpoints, 32 threads, 10 keys, instant receiver | 2,000 × `202`; 324 events/s; p50 74 ms, p95 190 ms, p99 924 ms | 2,000 DELIVERED; ~56 deliveries/s; completion p50 14.4 s, p95 26.9 s; done after 35.6 s | oldest due 23.8 s; pool 20 of 20 active, 7 waiting |
| Faults | 1,000 events, 8 endpoints, 16 threads, 10 keys, 50 ms receiver delay, 20 % `503` | 1,000 × `202`; 402 events/s; p99 578 ms | 995 DELIVERED, 5 DEAD_LETTERED after 4 attempts; 1.25 attempts per delivery; completion p50 9.2 s, p95 17.9 s (includes retry backoff); done after 33.5 s | due backlog peak 907; oldest due 11.6 s; pool peak 3 active |

**Observations**

1. **Ingestion is not the bottleneck.** Accepting stayed under 1 s at p95 while deliveries queued up behind it.
2. **Delivery throughput is the bottleneck.** About 56 deliveries a second with an instant receiver is well below what 32 concurrent requests could carry.
   - In the burst run the connection pool was fully used with requests waiting, so database work per delivery is the likely limit on this host.
   - This was **not profiled**, so it is a hypothesis (follow-up 6).
3. **Retry overhead in the fault run:** 0.25 extra attempts per delivery. 5 of 1,000 deliveries failed four times in a row and were dead-lettered, as configured.
4. **The backlog recovered fully in every run.** Nothing stayed PENDING or RETRYING.
5. **Gauge parsing:** the backlog-size gauge was read correctly only in the fault run. The first two runs used a version of the script that missed labelled gauges; this is fixed. Their "oldest due" and pool figures were read correctly.

## 6. Failed tests and root causes

The baseline had no failing tests. Every failure the audit saw came from a new test that exposed a defect (section 7), with two exceptions:

- **Docker daemon crashes.** The daemon in this container died twice during the audit, and the runs in progress failed with "Could not find a valid Docker environment". I restarted the daemon with `dockerd`, reran the affected tests, and they passed. These failures were caused by the environment, not by the code.
- **The single CI compose failure** described in 5.6.

## 7. Fixes and regression tests

Every fix has a regression test that failed before the fix. That failing run was recorded before the fix was written.

| Id | Severity | Defect | Root cause | Fix | Regression test |
|---|---|---|---|---|---|
| S1 | **P1** | Every API-key request created a `spring_session` row and returned a `WEBHOOKS_SESSION` cookie. The cookie alone authenticated as the API client, without the key being checked again, for up to 8 h | The `SecurityContextRepository` bean allowed session creation, and `SessionCreationPolicy.NEVER` does not apply to a repository supplied as a bean | The repository never creates sessions and never stores API-client authentication | AuditApiKeySessionTest, AuditOperationsProcessTest |
| E1 | **P1** | Two concurrent PATCHes of different fields could lose one change | `update` read the row without a lock and wrote back every column | `SELECT … FOR UPDATE` before merging | `concurrentPatchesOfDifferentFieldsDoNotLoseUpdates` |
| W1 | P2 | A late worker result racing the reaper caused a database deadlock, and the observation could be lost | Opposite lock order: attempt row then delivery row, versus delivery then attempt | Update the delivery row first | `lateWorkerResultRacingTheReaper…` |
| W2 | P2 | A delivery could stay RETRYING forever after its endpoint was deleted | Race between soft delete and retry scheduling | The reaper sweeps queued deliveries of deleted endpoints | `retryRecordedWhileTheEndpointIsBeingDeleted…` |
| W3 | P2 | A 2xx with a stalling or huge body was recorded as TIMEOUT and sent again | Closing the stream drained the rest of the body | Close with `CloseMode.IMMEDIATE` when the body was not fully read | `successfulResponseWithAStallingBodyIsDeliveredNotTimedOut` |
| D1 | P2 | During a database outage the API answered 401 "send a valid API key" | The failure happened inside the security filters and fell through to the authentication entry point | `DatabaseUnavailableFilter` and handler: `503 DATABASE_UNAVAILABLE` with `Retry-After` | AuditDatabaseOutageTest (observability) |
| D2 | P2 | One Prometheus scrape during an outage tried the database 7 times (up to 210 s) | A failed read was cached as already stale | Cache the failure for the normal cache period | AuditQueueMetricsOutageTest |
| D3 | P2 | The events list and the `eventType` filter scanned whole tables (111 ms at 50k events, growing) | Aggregation before LIMIT; missing account predicate | Page first, then count with LATERAL; add the account filter | AuditQueryPlanTest |
| A2 | P2 | An out-of-range `from`/`to` returned 500 and logged SQL | No range check before PostgreSQL | 400 `INVALID_PARAMETER` | `tamperedCursorsAndOutOfRangeTimestampsAre400NotServerErrors` |
| H1 | P2 | FAILING health was documented as "last 5 attempts failed", which is not what the code does | Docs drifted from PLAN §7.5 | Docs and javadoc corrected | `failingFollowsTheCircuitCounterNotRawAttemptFailures` |
| A1 | P3 | 404, 405 and 415 lacked `code` and `requestId` | Spring passes a null body to the handler | Build the body from the exception | `frameworkErrorsUseTheSameProblemShape` |
| A3 | P3 | Firewall rejections returned 401 | The error dispatch hit `denyAll` | `RequestRejectedHandler`: 400 `REQUEST_REJECTED` | AuditLiveHttpTest |
| A4 | P3 | OpenAPI: a phantom `token` parameter on `/auth/csrf`; `Idempotency-Key` marked optional | springdoc introspection | Fixed; contract and `schema.ts` regenerated | OpenApiContractTest |
| W4 | P3 | `Retry-After` rejected two legal date forms and accepted non-ASCII digits | Parser too narrow and too loose | Accept all three RFC 9110 forms, ASCII digits only | AuditRetryAfterTest |
| E2 | P3 | A whitespace-only endpoint name returned 500 | Trimmed to empty, then hit the database CHECK | 422 `VALIDATION_FAILED` | `whitespaceOnlyNameIsAValidationErrorNotAServerError` |
| F-SSRF | P3 | `::ffff:0:a.b.c.d` (IPv4-translated) was not blocked | Range missing from the block list | Block `::ffff:0:0:0/96` | `ipv4TranslatedFormIsBlocked` |
| M1 | P3 | A malformed signing secret crashed the simulator with 500 | No validation | 400 on `/verify` and on receiver config | `malformedSecretsAreRejectedNotServerErrors` |
| O1 | P3 | ARCHITECTURE.md named a metric that does not exist (`webhooks_deliveries_created_total`) | The Prometheus client reserves `_created` | Docs say `webhooks_deliveries_total` | AuditOperationsProcessTest |

Also changed:
- **PLAN:** a state-table row for IN_FLIGHT → CANCELLED.
- **TESTING.md:** counts, the browser scenarios and the benchmark.
- **CI:** Playwright annotations and the restart scenario.
- **Benchmark script:** reads labelled gauges.

## 8. Security findings and remaining risks

The two P1 defects (S1, E1) are fixed. These risks remain open:

- **Delivery semantics.** Delivery is at least once. A crash or lease expiry while a request is in progress can send the same `webhook-id` twice. Receivers must deduplicate.
- **Password hashing.** The Argon2id parameters (16 MiB, t=2) are slightly below the OWASP minimum of 19 MiB.
- **Login timing.** Whether a wrong password and an unknown email take the same time is not measured.
- **API key cap.** The 20-key cap is checked with count-then-insert, so concurrent creates can exceed it.
- **Payloads.** A lone UTF-16 surrogate in a payload is stored and delivered as `?`. The signature still matches the bytes sent; rejecting such payloads with 422 would be cleaner.
- **Rotation.** A second rotation inside the grace period drops the original secret at once. Only one previous secret is kept, and this is not documented.
- **Circuit and URL edits.** Changing an endpoint's URL keeps an OPEN circuit. The corrected URL is not tried until the probe or a manual reset.
- **Simulator.**
  - `SIMULATOR_CONTROL_TOKEN` has no minimum length.
  - Anyone can create receivers, up to the cap of 50, on a public simulator.
- **Deployment script and images.**
  - The Fly deploy script passes secrets as command-line arguments, which are visible in the local process list while it runs.
  - Base images and CI actions are not pinned by digest.
- **Startup credentials.** The base `application.yml` defaults the database credentials to `webhooks`/`webhooks` outside the local profile.
- **Tomcat 400 pages.** Tomcat's own 400 page for `%2f`, `%00` and `%5c` is bare HTML. It contains no version string.
- **Data retention.** There is none for events, deliveries or attempts, so tables grow without bound.
- **Health check.** `/actuator/health` takes 30 s (the Hikari connection timeout) to report a database outage.
- **Dependencies.**
  - Dev-only npm advisories through vitest 3 remain.
  - Java dependency freshness is BLOCKED.

## 9. Known limitations and untested scenarios

- **NOT VERIFIED:**
  - real DNS rebinding against a live resolver;
  - TLS error classification (no TLS receiver);
  - login timing equality;
  - connection-pool exhaustion under sustained load;
  - `requestId` inside JSON log lines;
  - rollback paths in replay and processing (review only);
  - C12 filters in combination, pagination, long-value layouts, and expired-session handling in the UI;
  - Windows-specific behaviour beyond the earlier PowerShell checks.
- **Never run:**
  - the hosted deployment (Fly.io + Neon): not deployed, by instruction;
  - `deploy-fly.sh`: reviewed only.
- **Poison data:** an undecryptable secret, or an HTTP-client runtime error before sending, is reported as a lease expiry, not as its real cause. This is bounded, and the delivery is dead-lettered after the retry budget.
- **Worker poll loop:** it catches only `RuntimeException`. A JVM `Error` in the claim query would stop the poll loop while the worker still reports itself as running.
- **Mistake that touched your stack.** While reproducing the CI failure, I ran `scripts/demo.sh` with a misnamed variable (`API` instead of `API_URL`), so it ran against your local stack on port 8080.
  - It added three endpoints named `demo-success-…`, `demo-recover-…` and `demo-replay-…`, with one delivery each, to your database.
  - Nothing was deleted: the script's cleanup only runs when there are more than 20 demo endpoints, and its log shows it did not run.
  - Your 72 deliveries are intact (read-only count).
  - You can delete the three endpoints in the dashboard.

## 10. Reproducing these results

On your machine, from the repository root, with your existing Compose stack running:

```bash
git fetch origin && git checkout stage-7-release && git pull

# Backend and simulator (needs Docker for Testcontainers; your Compose stack is not touched)
./mvnw -B clean verify

# Dashboard
cd frontend
npm ci
npm run typecheck && npm test -- --run && npm run build
npm run gen:api && git diff --exit-code src/api/schema.ts openapi.json
cd ..

# Rebuild and restart your stack with the fixes (keeps the database volume)
docker compose up -d --build

# Browser suite (wait a minute after other logins; 5 logins a minute per address)
cd frontend
npx playwright install chromium
E2E_RESTART_CMD="docker compose restart app" npm run e2e
cd ..

# Benchmark (creates and removes its own endpoints, receivers and keys)
python3 scripts/bench.py --events 200 --endpoints 4 --concurrency 8 --keys 4
```

On Windows PowerShell, set the restart command with `$env:E2E_RESTART_CMD = "docker compose restart app"`, then run `npm run e2e`. Use `python` instead of `python3` if that is how Python is installed. Never run `docker compose down -v`: it deletes the database volume.

## 11. Manual demonstration checklist

Each item is something you can do in the dashboard at http://localhost:8080 (sign in as the Compose admin), followed by the result to expect.

1. **Register an endpoint** at `http://simulator:9090/receivers/demo`. The signing secret is shown once.
2. **Register an unsafe URL** `http://169.254.169.254/` and `http://localhost:8080/`. Both are refused with an error message.
3. **Send a test event.** The delivery reaches DELIVERED, and the simulator's request log at `/receivers/demo/requests` shows signature `VALID`.
4. **Configure the receiver to answer 503 once.** The delivery shows two attempts about 1–2 s apart.
5. **Configure it to answer 400.** The delivery is dead-lettered after one attempt.
6. **Make the receiver fail every time.** The delivery shows four attempts, and Dead letters lists it.
7. **Fix the receiver, then Replay.** A new linked delivery succeeds, and the original keeps its history.
8. **Edit the endpoint URL.** Queued work goes to the new URL.
9. **Rotate the secret.** During the grace period, two signatures are sent.
10. **Fail the receiver 10 times.** The endpoint shows the circuit paused. Fix the receiver; the circuit closes after the cool-down and the backlog drains.
11. **Restart the app with `docker compose restart app` while a retry is pending.** The retry is delivered after the restart.
12. **Sign out, then open `/deliveries`.** You are sent back to the sign-in page.

## 12. Prioritised follow-up work

1. **Merge order:** merge #5, #6 and #7 in order, or retarget. Do not merge #5 or #6 alone, because the P1 fixes live in #7.
2. **(P2)** Pin base images and CI actions by digest, and add `distributionSha256Sum` to the Maven wrapper.
3. **(P2)** Lower the Hikari `connection-timeout`, so outages surface in seconds rather than 30 s.
4. **(P2)** Pass Fly secrets through `fly secrets import` on stdin, not as arguments.
5. **(P3)** Record pre-send failures (secret, client errors) as their own outcome rather than as lease expiry. Catch `Throwable` in the poll loop.
6. **(P3)** Profile delivery throughput, since the database pool saturated in the burst run, before choosing worker and pool sizes for any hosted plan.
7. **(P3)** Raise the Argon2id memory to at least 19 MiB. Lock the API-key cap check.
8. **(P3)** Document every response status in OpenAPI (201, 202, 204, errors, security schemes).
9. **(P3)** Add ESLint. Upgrade vitest to clear the dev advisories. Add a replay confirmation.
10. **(P3)** Offer a circuit reset after a URL change. Document the single-previous-secret rotation rule.
11. **(P3)** Add a retention policy for old events, deliveries and attempts.
12. **(P3)** Rerun the Java dependency check where Maven Central is reachable.
