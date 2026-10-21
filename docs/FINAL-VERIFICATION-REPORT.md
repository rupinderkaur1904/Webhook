# Final verification report

Date: 2026-10-17. This report rechecks the earlier audit ([AUDIT-REPORT.md](AUDIT-REPORT.md)) against the repository, fresh test runs, git history and CI. It does not rely on that report's wording.

Statuses: PASS, FAIL, BLOCKED, NOT VERIFIED, NOT APPLICABLE.
- **PASS** means I ran the check in this verification and saw the result. Where a PASS rests on a test run made only by CI, the row says so.
- **NOT VERIFIED** means the item was reviewed in the code but not executed.

## 1. What was verified

| Item | Value |
|---|---|
| Branch | `stage-7-release` (PR #7) |
| Starting point | `4146fa1a2bfa783548d6a73f4f92b381c4bce9a4`: identical to `origin/stage-7-release`, clean working tree, no staged or unstaged changes, no stashes |
| Commits added during this verification | `ff78ffa`: new API-key revocation test<br>`3b88844`: one documentation sentence corrected<br>`07eb146`: test-setup fix for the intermittent CI failure |
| Last code commit | `07eb14613a1dffdc8553795f8fde737ade3046c4`: all four CI jobs green. The commit that adds this report changes only this file |
| Product code changed since `4146fa1` | None. The diff is one new test file, one test helper, `docs/ARCHITECTURE.md`, and a javadoc comment in `HealthStatus.java` |
| PRs | #5, #6 and #7 are open drafts; none merged or closed. Bases: #5 → `main`, #6 → `stage-5-security`, #7 → `stage-6-observability`. GitHub reports all three as cleanly mergeable |

Nothing was deployed, merged or closed. Your local database was only read, through read-only transactions, and it was not modified. The browser tests and the cleanup rehearsal used a separate Compose project, `wver`, on ports 5442/8092/9092 with its own new volume.

## 2. The 18 findings, rechecked one by one

**Method.** For each code fix:
1. Remove only the production change, keeping the test.
2. Run the named regression test and record the failure.
3. Restore the code and run the test again.

All of this was done in a separate worktree at `4146fa1`.

**Suite membership.** Every test class runs in `./mvnw verify`:
- no surefire excludes;
- no `@Disabled` or `assume*`;
- all 62 test classes produced surefire reports;
- 0 tests skipped.

| Finding | Sev. | Root cause | Fix commit | Regression test | Without the fix | With the fix | Remaining risk |
|---|---|---|---|---|---|---|---|
| S1 API-key requests created sessions | P1 | The `SecurityContextRepository` bean allowed session creation and stored API-client authentication | `89a0144` | `AuditApiKeySessionTest`, `AuditOperationsProcessTest`, new `VerifyApiKeyRevocationTest` | FAIL: a cookie from an API-key response still got `200` after the key was revoked | PASS | See 2.1. The revoke-and-reuse half of `AuditApiKeySessionTest` only runs when a cookie exists, so after the fix it is skipped. The new test covers it unconditionally. |
| E1 concurrent PATCH lost an update | P1 | The row was read without a lock and every column written back | `9c8a0fe` | `AuditEndpointLifecycleTest#concurrentPatchesOfDifferentFieldsDoNotLoseUpdates` | FAIL: URL stayed `…/a`, expected `…/moved` | PASS | No optimistic versioning (see 2.2) |
| W1 reaper / late-result deadlock | P2 | Opposite lock order (attempt row before delivery row) | `b2b9434` | `AuditWorkerFaultsTest#lateWorkerResultRacingTheReaper…` | FAIL: `deadlock detected` | PASS | The test copies the reaper's SQL order by hand, so a later change to the reaper would not be caught |
| W2 delivery stuck after endpoint delete | P2 | Race between soft delete and retry scheduling | `b2b9434` | `AuditWorkerFaultsTest#retryRecordedWhileTheEndpointIsBeingDeleted…` | FAIL: `RETRYING`, expected `CANCELLED` | PASS | The sweep runs on every reaper pass without `SKIP LOCKED`, which costs extra with many instances (NOT VERIFIED) |
| W3 2xx with a stalling body became TIMEOUT | P2 | Closing the stream drained the rest of the body | `763c98c` | `AuditRetryClassificationTest#successfulResponseWithAStallingBody…` | FAIL: `TIMEOUT`, expected `SUCCESS` | PASS | A body that stalls before its first 2 KB still times out |
| D1 database outage answered 401 | P2 | The failure happened inside the security filters | `5614a83` | `observability.AuditDatabaseOutageTest` | FAIL: `500` (or `401` without fix A3), expected `503` | PASS | The test does not assert the `Retry-After` header |
| D2 seven database tries per scrape in an outage | P2 | A failed read was cached as already stale | `b61e537` | `AuditQueueMetricsOutageTest` | FAIL: 7 round trips, expected 1 | PASS | None found |
| D3 full scans on events list and eventType filter | P2 | Aggregation before LIMIT; no account predicate | `4dbfdc9` | `AuditQueryPlanTest` | FAIL: sequential scans on `events` and `deliveries` | PASS | Depends on PostgreSQL planner choices |
| A2 out-of-range date filter returned 500 | P2 | No range check before SQL | `c7a9c74` | `AuditHttpSecurityTest#tamperedCursorsAndOutOfRange…` | FAIL: `500`, expected `400` | PASS | None |
| H1 FAILING rule documented wrongly | P2 | Docs drifted from PLAN §7.5 | `33aa13c` (docs); one more sentence corrected in `3b88844` | `AuditCircuitHealthTest#failingFollowsTheCircuitCounter…` | NOT APPLICABLE (docs only; the test asserts the real behaviour) | PASS | None |
| A1 404/405/415 lacked `code` and `requestId` | P3 | Spring passes a null body to the error handler | `a7fa259` | `AuditIngestionContractTest#frameworkErrorsUseTheSameProblemShape` | FAIL: no `$.code` | PASS | None |
| A3 firewall rejections answered 401 | P3 | The error dispatch hit `denyAll` | `0b491a2` | `AuditLiveHttpTest` | FAIL: `401`, expected `400` / `405` | PASS | A direct `GET /error` still returns 401 (asserted) |
| A4 OpenAPI errors | P3 | springdoc introspection | `8d8db22` | `OpenApiContractTest` | FAIL: contract drift | PASS | The test only checks drift. I checked by hand that the committed contract has no `token` parameter and marks `Idempotency-Key` required |
| W4 `Retry-After` parsing | P3 | Only one HTTP-date form accepted; non-ASCII digits accepted | `2e827b7` | `AuditRetryAfterTest` | FAIL (2 tests) | PASS | None |
| E2 whitespace-only name returned 500 | P3 | Trimmed to empty, then hit the database CHECK | `9c8a0fe` | `AuditEndpointLifecycleTest#whitespaceOnlyName…` | FAIL: `500`, expected `422` | PASS | None |
| F-SSRF IPv4-translated IPv6 not blocked | P3 | Range missing from the block list | `c167f1e` | `AuditSsrfMatrixTest#ipv4TranslatedFormIsBlocked` | FAIL | PASS | Tested at the address-policy level only |
| M1 malformed secret crashed the simulator | P3 | No secret validation | `feddf50` | `AuditSimulatorAuthTest#malformedSecretsAreRejected…` | FAIL: `500` / `200`, expected `400` | PASS | None |
| O1 wrong metric name in the docs | P3 | Prometheus drops the reserved `_created` suffix | `89a0144` (docs) | `AuditOperationsProcessTest` checks the real name | NOT APPLICABLE (docs only) | PASS | None |

**Result.** All 18 fixes are present at the final commit.
- **16 code fixes:** each regression test failed without its fix and passed with it.
- **2 documentation-only fixes (H1, O1):** the documentation matches the code, and their tests assert the real behaviour.

### 2.1 S1: how an API-key request is handled

1. **Servlet filters run first.**
   - `RequestIdFilter`
   - `DatabaseUnavailableFilter`
   - `RequestSizeLimitFilter`
   - Spring Session's `SessionRepositoryFilter`, which creates a session only when code calls `getSession(true)`.
2. **The Spring Security chain** (`config/SecurityConfig.java`, around lines 61–110):
   1. The context is loaded from the repository.
   2. CSRF is skipped for `Authorization: Bearer` requests.
   3. **`ApiKeyAuthenticationFilter`** (`auth/ApiKeyAuthenticationFilter.java:53-68`) hashes the key and looks it up among active keys. An unknown or revoked key is answered with `401` immediately. A valid key replaces any session authentication with `ROLE_API_CLIENT`.
   4. Session management runs, with policy `NEVER`.
   5. Authorization runs.
3. **Before the fix.** The repository was a plain `HttpSessionSecurityContextRepository`, which may create sessions. `SessionCreationPolicy.NEVER` does not apply to a repository supplied as a bean. Each API-key call therefore saved its authentication into a new session and returned a `WEBHOOKS_SESSION` cookie. That cookie alone authenticated as the API client, without the key being checked again, until it was idle for 8 hours.
   - Reproduced again in this verification with the fix reverted: the cookie returned `200` after the key had been revoked.
4. **Now** (`SecurityConfig.java`, around lines 153–192):
   - The repository has `setAllowSessionCreation(false)`.
   - A wrapper refuses to save any authentication holding `ROLE_API_CLIENT`.
   - Browser login still works, because the login endpoint creates its session itself (`AuthController.java:105`). `SessionAuthTest` passes 11/11.
5. **Other state paths** (checked in the code):
   - The CSRF token lives in a cookie, not in the session.
   - The request cache is disabled.
   - The login is the only `getSession(true)` in production code.
6. **New regression test.** `VerifyApiKeyRevocationTest` runs over real HTTP:
   - it logs in, creates a key, uses it for reads and event submission, and revokes it;
   - it then requires `401` for the key, for any cookie the key's responses set, and for both together;
   - the browser session must still answer `200`;
   - no session cookie, session row or stored API-client context may be created.

   It fails without the fix and passes with it.
7. **Residual gap (code reading, NOT VERIFIED by a test).** Loading a context does not filter out API-client authentications. So session rows created by API keys *before* this fix would still authenticate until they expire.
   - **No deployment** has ever run the old code.
   - **Your local database** has 0 rows in `spring_session` (read-only count, 2026-10-17). So nothing is exposed today.
   - **Recommended hardening (P3):** also ignore API-client contexts when loading a session.

### 2.2 E1: how concurrent endpoint edits are handled

`EndpointService.update` runs in one transaction:

1. An unlocked existence check (so a missing endpoint gets `404` first).
2. Name validation.
3. URL validation and the DNS check, deliberately before the lock.
4. `SELECT … FOR UPDATE` on the endpoint row (`EndpointRepository.findForUpdate`).
5. Fields the request leaves out are filled from the locked row, then the update is written.

Two PATCHes of the same endpoint therefore run one after the other, and the second sees the first's committed values. Re-verified: with the lock removed, the test loses the URL change.

**Interactions**
- **Secret rotation** updates different columns, so it never conflicted.
- **Soft delete during a waiting PATCH:** the PATCH gets `404`.
- **Deadlocks:** PATCH locks one endpoint row and no delivery rows, so it cannot form a lock cycle with the worker.

**Limitation.** There is no optimistic concurrency control (no version, ETag or `If-Match`). The dashboard's edit form sends every field as it was when the page loaded. If someone else changes the endpoint between that page load and **Save**, their change is overwritten without warning. The lock only prevents two server-side updates from interleaving.

## 3. Stage 5 (security) acceptance matrix

Sources: PLAN §8 and milestone M4, and the Stage 5 implementation notes.

| Criterion | Evidence (executed in this verification unless noted) | Status |
|---|---|---|
| Passwords hashed with Argon2id; legacy bcrypt upgraded on login | `SessionAuthTest#newHashesAreArgon2id`, `#legacyBcryptHashIsUpgradedToArgon2idOnLogin` | PASS. Memory cost of 16 MiB is below the OWASP 19 MiB minimum (P3) |
| Wrong password and unknown email give the same response | `SessionAuthTest` (2 tests) | PASS. Timing equality NOT VERIFIED |
| Session persisted server-side; cookie HttpOnly, Secure, SameSite=Lax; 8 h idle expiry | `SessionAuthTest#sessionCookieAttributesIdleTimeoutAndLogoutCsrf`, `#loginCreatesAPersistedSession…` | PASS |
| Logout invalidates the session server-side; a captured cookie is useless afterwards | `SessionAuthTest#logoutRevokesTheSessionServerSide`; browser test "the old session cookie is useless after sign-out" | PASS |
| A new login issues a new session id and kills the old one | `SessionAuthTest#loggingInAgainIssuesANewSessionIdAndKillsTheOldOne` | PASS |
| API keys only ingest and read; 403 on every management route | `AccessControlTest` (11 routes), `AuditHttpSecurityTest#apiKeysCanOnlyIngestAndRead` (all routes discovered from Spring MVC); browser test | PASS |
| API-key requests cannot create browser sessions | `AuditApiKeySessionTest`, `VerifyApiKeyRevocationTest`, `AuditOperationsProcessTest` | PASS (after S1). Pre-fix session rows: see 2.1 |
| A revoked API key stops working | `CredentialsAndSecretsTest#apiKeyIsShownOnceWorks…StopsWorkingWhenRevoked`; `VerifyApiKeyRevocationTest`; browser test | PASS |
| Browser session and API key stay isolated (a Bearer header replaces the session's rights) | `AccessControlTest#aBearerHeaderOverridesTheSession…`; `VerifyApiKeyRevocationTest` (session still 200, key 401) | PASS |
| CSRF on every state-changing session route, including login and logout | `AuditHttpSecurityTest#everyStateChangingRouteNeedsTheCsrfToken…` (13 routes), `SessionAuthTest` | PASS |
| Cross-account isolation | `AccessControlTest#anotherAccountGets404OnEveryRoute…`, `AuditHttpSecurityTest` | PASS |
| Signing secrets encrypted at rest (AES-256-GCM bound to the endpoint), shown only on create and rotate | `SecretCipherTest` (5), `AuditEndpointLifecycleTest#secretsAreEncryptedAndBound…`, `#secretsAppearOnlyInCreateAndRotateResponses` | PASS |
| Rotation sends both signatures during the grace period; old secret retired exactly at expiry | `CredentialsAndSecretsTest` (3), `AuditEndpointLifecycleTest` (3), `AuditSigningContractTest`; browser test | PASS. Only one previous secret is kept (undocumented, P3) |
| URL validation and SSRF at registration and update | `EndpointApiTest`, `AuditSsrfMatrixTest` (80), `AuditEndpointLifecycleTest#urlChangesRunTheSameValidation…`; browser test | PASS. Scope in section 8 |
| SSRF at send time, redirects never followed | `AuditSsrfMatrixTest#sendTimeResolverRefuses…`, `#realClientRefusesLocalListener…`, `#realClientNeverFollowsRedirects…` | PASS |
| Login rate limit (per address and per email) and ingestion rate limit (per key) | `RateLimiterTest` (3), `LiveServerLimitsTest` (3), `TrustedProxyTest`, `UntrustedProxyTest` | PASS. The limits are per instance |
| Body limit 256 KB (declared and chunked); JSON depth limit | `CredentialsAndSecretsTest#oversizedBodies…`, `#absurdlyNestedJson…`; `LiveServerLimitsTest#chunkedBody…` | PASS |
| Security headers on API, errors, assets and SPA; no permissive CORS | `SessionAuthTest#responsesCarryBrowserHardeningHeaders`, `AuditHttpSecurityTest` (2) | PASS |
| No secrets in logs, responses or errors | `CredentialsAndSecretsTest#secretsAndKeysNeverReachTheLogs`, `LogMaskingTest`, `AuditOperationsProcessTest` (real JVM, JSON logs) | PASS |
| Concurrent security-sensitive state changes | Concurrent PATCH (E1), PATCH vs rotation, concurrent same idempotency key, concurrent replay: all tested | PASS. The 20-API-key cap is checked with count-then-insert and has no concurrency test (P3, open) |

**Verdict.** Every Stage 5 criterion in the plan passes on Stage 7. That is not true of the Stage 5 branch itself (PR #5): it still has S1 (`SecurityConfig.java:124` returns a plain repository), E1 (no row lock) and the SSRF gap. **Stage 5 is complete only as contained in Stage 7.**

## 4. Stage 6 (reliability and observability) acceptance matrix

Sources: PLAN §7.5 and milestone M5, and the Stage 6 implementation notes.

| Criterion | Evidence | Status |
|---|---|---|
| Opens at exactly 10 consecutive failures, not before | `CircuitBreakerTest#opensAfterThresholdConsecutiveFailures`, `AuditCircuitHealthTest#opensExactlyAtTheThresholdAndNotBefore` | PASS |
| Open → half-open after cool-down; one probe even with concurrent claimers | `CircuitBreakerFlowTest`, `AuditCircuitHealthTest#concurrentProbeSelectionAndClaimingYieldExactlyOneProbe` (8 loops, 8 workers) | PASS |
| A successful probe closes the circuit and the backlog drains without spending its retry budget | `CircuitBreakerTest#probeSuccessOrReachableAnswerCloses`, `CircuitBreakerFlowTest#opensPausesProbesAndCloses…`; browser test (open, hold, recover, drain) | PASS |
| A failed probe reopens with a longer cool-down (doubling up to a cap) | `CircuitBreakerTest#probeFailureReopensWithALongerCooldown`, `#cooldownDoublesUpToTheCap` | PASS |
| Manual reset from the dashboard only; another account gets 404 | `CircuitBreakerFlowTest#circuitCanBeResetByHandFromTheDashboardOnly`; API keys get 403 (`AuditHttpSecurityTest`) | PASS |
| `429` and blocked destinations are neutral; a `400` proves the endpoint is reachable; 5xx and timeouts count | `CircuitBreakerTest#classifiesOutcomes` (7 cases), `#successAndReachableAnswersEndTheStreak…`, `CircuitBreakerFlowTest#rateLimitingNeverOpensTheCircuit`, `#nonRetryableAnswersProve…` | PASS |
| Health rules (DISABLED / FAILING / DEGRADED / HEALTHY / NO_DATA), windows, percentiles, no division by zero | `CircuitBreakerTest#classifiesHealth` (10), `AuditCircuitHealthTest` (4) | PASS. The docs sentence was corrected in `3b88844` |
| Dashboard wording does not present a closed circuit as success | `EndpointHealth.test.tsx` (frontend suite) | PASS |
| List, detail, health route and overview agree | `AuditCircuitHealthTest#listDetailHealthRouteAndOverviewAgree` | PASS |
| Prometheus counters change by the exact amounts | `AuditOperationsProcessTest` (exact deltas for 9 series) | PASS |
| Metrics need a token; nothing else on actuator is exposed | `PrometheusEndpointTest#scrapeRequiresTheMetricsToken`, `AuditHttpSecurityTest#noActuatorEndpointOtherThanHealth…`. Spot check on the isolated stack: no token → 401, wrong token → 401; `env`, `beans`, `heapdump`, `loggers`, `mappings`, `metrics` and others → 401; `health` shows status only | PASS |
| Bounded label cardinality | `AuditOperationsProcessTest`. Spot check: 205 series, 0 label values containing a UUID | PASS |
| JSON logs with delivery ids | `AuditOperationsProcessTest`, `DeliveryLogContextTest` | PASS |
| `requestId` in log lines | Plain format: replay log lines carry the request id. JSON format: an isolated app with `LOG_FORMAT=ecs` logged the replay with `"requestId":"verify-replay-0002"`, the value sent in `X-Request-Id` | PASS (previously NOT VERIFIED) |
| Queue gauges survive a database outage (NaN, one try per scrape) | `AuditQueueMetricsOutageTest` | PASS (after D2) |
| Backend state, metrics and UI agree numerically | API-level agreement tested (`DashboardQueriesTest`, `AuditCircuitHealthTest`). The browser test only checks that the overview labels and rows appear | NOT VERIFIED for UI numbers against Prometheus |

**Verdict.** Every Stage 6 criterion in the plan passes on Stage 7. The Stage 6 branch itself (PR #6) still has S1, E1 and the SSRF gap from Stage 5, the outage-scrape defect (D2) and the wrong FAILING documentation. **Stage 6 is complete only as contained in Stage 7.**

Only partly covered:
- numeric agreement between the dashboard and Prometheus;
- the W2 sweep under several instances (code reading only);
- `Retry-After` on the 503 database-outage answer (not asserted).

## 5. Test commands and results

| Suite | Command | Where | Commit | Exit | Result |
|---|---|---|---|---|---|
| Backend + simulator | `./mvnw -B clean verify` | local, 4 CPUs | `4146fa1` | 0 | **543 + 79**, 0 failures, 0 errors, 0 skipped (190 s) |
| Backend + simulator | `./mvnw -B clean verify` | local, 4 CPUs | `07eb146` | 0 | **544 + 79**, 0 / 0 / 0 (221 s) |
| Backend + simulator | `taskset -c 0,1 ./mvnw -B verify` | local, pinned to 2 CPUs like a CI runner | `07eb146` | 0 | **544 + 79**, 0 / 0 / 0 (275 s) |
| Frontend | `npm run typecheck`; `npm test -- --run`; `npm run build`; `npm run gen:api` + `git diff --exit-code` | local | `4146fa1` (no frontend change since) | 0, 0, 0, 0 | **36 tests** in 8 files; no contract diff |
| Lint | none configured | | | | NOT APPLICABLE (no ESLint; P3) |
| Browser | `npx playwright test` with `E2E_RESTART_CMD` set | local, fresh `wver` stack on a new empty volume, images built from HEAD; CI's demo sequence run first | `4146fa1` (no product change since) | 0 | **18 passed**, 0 skipped (105 s) |
| Receiver examples | `javac … && java VerifyTest.java`; `node --test`; `python3 -m unittest` | local | `4146fa1` | 0, 0, 0 | Java 22, Node 19, Python 19 |
| All four CI jobs | GitHub Actions | CI | `4146fa1` | | all success |
| All four CI jobs | GitHub Actions | CI | `3b88844` | | all success |
| All four CI jobs | GitHub Actions | CI | `07eb146` | | all success |

**Counts against the earlier audit**
- Backend 543 still matches at `4146fa1`. It is now **544**, because this verification added `VerifyApiKeyRevocationTest`.
- Simulator 79, dashboard 36 and browser 18 all match.
- All 62 test classes ran (56 backend, 6 simulator), and no test is disabled or conditionally skipped.

**Still blocked or not verified**
- The Java dependency CVE and freshness check is BLOCKED: Maven Central is unreachable from this environment.

## 6. CI status

| Commit | backend | frontend | receiver-examples | compose |
|---|---|---|---|---|
| `4146fa1` (start) | success | success | success | success |
| `3b88844` | success | success | success | success |
| `07eb146` (last code commit) | success (3 min 1 s) | success | success | success |

## 7. The intermittent backend CI failure

**What could be recovered**
- Job logs and artifacts redirect to blob storage, which this network refuses (403). The failing test's name is lost: annotations only began naming failed tests from `a3ba8ca` onwards.
- Thirteen workflow runs exist on `stage-7-release`. The backend job failed three times:
  - **`c72ef8e` and `12bb3ec` (2026-10-15):** these came before the test pool cap of `92c2dac`, which fixed a "too many clients" failure seen then;
  - **`d06b1dc` (2026-10-16):** the run in question. Its "Build and test" step ran 2 min 38 s, against 2 min 44 s to 3 min 15 s for successful runs. That fits the backend tests running to completion with at least one failure, followed by Maven skipping the simulator module. It does not fit a hang.
- `d06b1dc` was the first commit containing the database and operations audit's process tests. These start separate platform JVMs against the same test PostgreSQL.

**Investigation**
- About 45 runs before the container restart, all on `4146fa1`, with no failure:
  - the delivery package 10 times on 4 CPUs;
  - the delivery package 3 times on 2 CPUs with busy-loop CPU hogs;
  - the process tests 3 times;
  - `AuditStartupConfigTest` 8 times, plus 14 more with extra idle connections held open;
  - the full suite in reverse and in random order (3 runs).
- Connection sampling during `AuditStartupConfigTest`:
  - Some first iterations reached 100 client connections, which is PostgreSQL's `max_connections`.
  - Repeated in this verification: the peak reached 92, and one sampling connection was refused with **`FATAL: sorry, too many clients already`**.
- **Cause.** The test starts nine platform JVMs at once against the shared test database. Each JVM opened the production default pool of 20 connections, alongside the cached Spring contexts of the main test JVM.

**Fix (`07eb146`, test setup only).** `PlatformProcess` now starts platform processes with `DATABASE_POOL_SIZE=3`. Results after the fix:
- peak connections during the same test: **20**;
- all four process tests pass;
- the full suite passes on 4 CPUs and pinned to 2 CPUs.

**Conclusion**
- **Demonstrated:** a test-environment defect that exhausts the test database's connections. It fits the failure's timing and the earlier "too many clients" history.
- **Not proven:** that this was the `d06b1dc` failure. The log is unavailable, and the failure itself was not reproduced locally. This stays an **unresolved uncertainty**.
- **Follow-up:** CI now names failing backend tests in check annotations. If the failure recurs, the annotation will identify it.

## 8. Outstanding risks

| Item | Classification | What was tested / what remains |
|---|---|---|
| Pre-fix API-key sessions keep authenticating until they expire | Fixed for new sessions. For old ones: must fix before public deployment **only if** pre-fix code ever ran publicly (it has not) | No API-key sessions exist locally (0 session rows). Hardening the load path is recommended (P3) |
| No optimistic concurrency on endpoint edits (stale form overwrites) | Open, acceptable for local development; should fix before multi-user use | Server-side race fixed and tested; stale-form overwrite untested |
| **DNS rebinding.** Tested: registration and update reject ~55 hostile URL spellings; the send-time resolver refuses every blocked spelling; IP literals go through that resolver; the real client never connects to a local listener even with the pre-send check skipped; redirects are never followed. **Untested:** a live DNS server changing its answers between checks, and resolver poisoning | Not verified against a live attacker | The design connects only to the addresses the checking resolver returned, which closes the rebinding window. That is shown in code and tests, not against a real rebinding server. An egress firewall is still recommended for public hosting (PLAN §8.1) |
| Other SSRF limits: internal services on public IPs; any port allowed; IPv4-translated range checked at the address-policy level only | Must fix before public deployment (egress firewall) | Documented in PLAN §8.1 |
| Java dependency vulnerabilities and freshness | Blocked by environment | npm production dependencies: 0 advisories. Dev only: 2 critical and 1 moderate through vitest 3 |
| Production deployment (Fly.io + Neon) never run | Not verified | `deploy-fly.sh` reviewed only |
| Deploy script passes secrets as command-line arguments | Must fix before public deployment | Use `fly secrets import` on stdin |
| Base images and CI actions not pinned by digest | Must fix before public deployment (supply chain) | |
| Hikari 30 s connection timeout: health takes 30 s to show an outage | Open, acceptable locally; tune before public deployment | |
| Argon2id 16 MiB; API-key cap race; no data retention; Tomcat bare 400 page; undocumented single previous secret; URL edit keeps an open circuit; pre-send failures reported as lease expiry; poll loop catches only `RuntimeException` | Open, acceptable for local development (P3) | |
| Simulator: no minimum token length; anyone can create receivers up to 50 | Must fix before exposing a public simulator | |
| Dashboard: filters in combination, pagination, long values in layouts, expired session in the UI, replay confirmation (absent), lint (absent) | Not verified / open P3 | |
| Login timing equality | Not verified | |
| Delivery is at least once (duplicates possible after a crash or lease expiry) | By design; receivers must deduplicate on `webhook-id` | Covered by fencing and crash tests |

## 9. Merge readiness

**Relationships between the PRs**
- `stage-5-security` is contained in `stage-6-observability`, which is contained in `stage-7-release`.
- `main` (`194b875`) is not an ancestor of Stage 7, but `git merge-tree` merges Stage 7 into `main` without conflicts. The merged tree is **identical** to Stage 7's tree, because `main` has no content that Stage 7 lacks.

**Fixes needed for Stage 5 and 6 correctness live only in Stage 7**
- S1, E1 and F-SSRF: confirmed absent on both `stage-5-security` and `stage-6-observability`.
- W1, W2, W3, D1, D2, D3, A1, A2 and A3.
- The documentation corrections.

So merging #5 or #6 on its own would put known P1 defects on `main`.

**Recommended sequence**

Preferred:
1. A human reviews #7. It is large: 134 files, about 14,000 added lines against Stage 6.
2. Change #7's base to `main` and merge it with a merge commit.
3. Close #5 and #6 as superseded. GitHub may already show them as merged, because their commits will then be in `main`.

This way `main` never contains the Stage 5 or 6 defects.

Alternative, if you want each stage's history on `main` separately: merge #5, retarget #6 to `main` and merge it, retarget #7 and merge it, all in one sitting and with no deploy in between.

**Is #7 ready?**
- **For code review: yes.** CI is green on the exact head commit (see 6), the suites pass locally, and every fix has a regression test that was shown to fail without it.
- **For merge:** after that review.
- **For deployment: no** (section 8).

Nothing was merged or closed.

## 10. Your local data and how to remove the three demo endpoints

**Read-only inspection** of your `webhook` stack (2026-10-17, inside `BEGIN READ ONLY`):
- 72 deliveries and 126 attempts. The 72 already **include** the 3 demo deliveries, so your own deliveries number 69.
- Endpoints `demo-success-1792121847-26309`, `demo-recover-1792121848-28130` and `demo-replay-1792121849-26827` are active, with one DELIVERED delivery each.
- 4 of your 39 endpoint rows are active, and 3 of those 4 are the demo endpoints.

**How deletion behaves**
- Deleting an endpoint is a **soft delete**. The row stays with `deleted_at` set, it disappears from the endpoint list, and its name becomes free.
- Queued or retrying deliveries are cancelled. Delivered or dead-lettered deliveries are untouched.
- **Delivery history is kept.** The deliveries and attempts remain in the database and in the Deliveries and Events lists.
- The dashboard has no delete button, so deletion goes through the API.

**Procedure.** I rehearsed this on the isolated stack with look-alike endpoints: all three were removed, and their deliveries remained. On your machine, in PowerShell, with the stack running:

```powershell
$base = "http://localhost:8080"
Invoke-RestMethod "$base/api/v1/auth/csrf" -SessionVariable s | Out-Null
$xsrf = ($s.Cookies.GetCookies($base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$cred = @{ email = "dev@localhost"; password = "local-dev-password" } | ConvertTo-Json   # or your ADMIN_EMAIL / ADMIN_PASSWORD
Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -WebSession $s -ContentType 'application/json' -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -Body $cred | Out-Null
$xsrf = ($s.Cookies.GetCookies($base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$demo = (Invoke-RestMethod "$base/api/v1/endpoints" -WebSession $s).items | Where-Object { $_.name -match '^demo-(success|recover|replay)-17921218' }
$demo | Select-Object id, name      # check that exactly these three are listed before going on
$demo | ForEach-Object { Invoke-RestMethod "$base/api/v1/endpoints/$($_.id)" -Method Delete -WebSession $s -Headers @{ 'X-XSRF-TOKEN' = $xsrf } }
```

Do not delete rows in PostgreSQL directly. Never run `docker compose down -v`: it deletes the whole database volume.

## 11. Audit complete, merge-ready, production-ready: three different things

- **Audit and verification: complete.**
  - All 18 findings re-verified individually.
  - Stage 5 and Stage 6 criteria pass on Stage 7.
  - The intermittent CI failure was investigated, a likely cause was fixed, and the remaining uncertainty is recorded.
- **Merge readiness: ready for human code review.** Merge #7 into `main` after review (section 9). Do not merge #5 or #6 on their own.
- **Production readiness: not established.**
  - The deployment has never been run.
  - Java dependency scanning is blocked.
  - Several items must be fixed before public exposure (section 8): egress firewall, secret handling in the deploy script, image pinning, simulator hardening, and edit concurrency for multi-user use.
