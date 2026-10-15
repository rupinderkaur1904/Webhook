# Developer setup

To simply run and try the platform, use Docker Compose: see [LOCAL-TESTING.md](LOCAL-TESTING.md). This page is for working on the code: running the backend, simulator and dashboard from source with hot reload.

## Run from source

Prerequisites: Java 21, Node 22, and PostgreSQL 16 (or Docker for the database). On Windows use `mvnw.cmd` instead of `./mvnw`, and run `scripts/*.sh` from WSL or Git Bash.

```bash
docker compose up -d db          # or use an existing PostgreSQL with a 'webhooks' database/user

./mvnw -pl backend -am package -DskipTests
SPRING_PROFILES_ACTIVE=local java -jar backend/target/webhook-platform-0.1.0-SNAPSHOT.jar   # :8080

./mvnw -pl receiver-simulator package -DskipTests
SIMULATOR_OPEN_CONTROL_API=true java -jar receiver-simulator/target/receiver-simulator-0.1.0-SNAPSHOT.jar   # :9090

cd frontend && npm ci && npm run dev                                                        # :5173, proxies /api to :8080
```

A jar built with `./mvnw package` does not contain the dashboard (the Docker build adds it), so while developing open the dashboard from the Vite dev server at http://localhost:5173.

The `local` profile creates the bootstrap account `dev@localhost` / `local-dev-password`, which signs in to the dashboard, and the API key `whk_local_dev_key_0000000000000000000000` for submitting events. It also allowlists the simulator on `localhost:9090` and allows the session cookie over plain HTTP. Create more API keys on the dashboard's **API keys** page.

Production configuration uses these environment variables:
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- `SECRET_ENCRYPTION_KEY` (base64 of 32 random bytes, for example from `openssl rand -base64 32`)
- `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD`, `BOOTSTRAP_API_KEY`
- `REGISTRATION_ENABLED` (default `false`), `SESSION_TIMEOUT` (default `8h`)
- `SESSION_COOKIE_SECURE` (default `true`; set `false` only for plain-HTTP local development)
- `TRUSTED_PROXIES` (CIDR ranges of the reverse proxy; see [SECURITY.md](SECURITY.md#reverse-proxies-and-client-addresses)), `PROXY_DIAGNOSTICS`
- `PORT` (default `8080`), `DATABASE_POOL_SIZE` (default `20`)
- `METRICS_TOKEN` (enables `/actuator/prometheus` for a scraper holding it), `LOG_FORMAT` (empty for plain text, `ecs` or `logstash` for JSON)
- `CIRCUIT_FAILURE_THRESHOLD` (default `10`), `CIRCUIT_BASE_COOLDOWN` (`30s`), `CIRCUIT_MAX_COOLDOWN` (`30m`)

The app refuses to start without a valid encryption key.

## Demo

With the backend (`local` profile) and simulator running from source. Against Docker Compose, add `SIMULATOR_URL_FROM_API=http://simulator:9090`, because the platform reaches the simulator by its service name:

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

## Failure simulator

The simulator runs as its own app on port 9090. Webhooks go to `POST /receivers/{name}`: register endpoints as `http://localhost:9090/receivers/<name>` (or `http://simulator:9090/receivers/<name>` under Compose); a receiver is created on first use. Without `SIMULATOR_CONTROL_TOKEN` it starts only with `SIMULATOR_OPEN_CONTROL_API=true` (see [SECURITY.md](SECURITY.md#the-public-receiver-simulator)). `PUT /receivers/{name}/config` accepts the following fields:

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
