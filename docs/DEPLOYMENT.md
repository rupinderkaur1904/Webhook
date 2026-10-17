# Deployment

The public demo runs on **Fly.io** (the platform and the receiver simulator as two separate apps) with **Neon** as the managed PostgreSQL. The comparison behind that choice is in the project's hosting notes; this page covers how to deploy and operate it.

> To try the platform without deploying anything, use Docker Compose: [LOCAL-TESTING.md](LOCAL-TESTING.md).

## Topology and cost

| Piece | Where | Size | Approx. cost |
|---|---|---|---|
| Platform (`webhook-platform-demo`) | Fly.io, region `iad`, one always-on machine | shared-cpu-1x, 1 GB | ~$6.70 / month |
| Simulator (`webhook-simulator-demo`) | Fly.io, region `iad`, one always-on machine | shared-cpu-1x, 512 MB | ~$3.69 / month |
| PostgreSQL | Neon **Launch** plan, AWS `us-east-1` (next to `iad`), scale-to-zero off | 0.25 CU | ~$19.70 / month |

About **$30 a month** in total. Neon's Free plan does not work for this app: the delivery worker polls the database every 500 ms, so the database never idles and the free compute hours run out in about two weeks.

Both Fly apps stay on (`auto_stop_machines = "off"`, `min_machines_running = 1`). The platform's worker must keep running to deliver and retry, and the simulator keeps its state in memory. Fly sends `SIGTERM` with a 30 s grace period (`kill_signal`, `kill_timeout`), which matches the platform's 20 s graceful shutdown; deploys are blue-green, so the new machine is healthy before the old one stops.

## What you need

- A Fly.io account and `flyctl` (https://fly.io/docs/flyctl/install/), and a deploy token: `fly tokens create org`.
- A Neon project on the Launch plan in AWS `us-east-1`, with scale-to-zero disabled. Copy its connection string (`postgresql://user:password@host/db?sslmode=require`).
- bash, `jq`, `openssl` and `python3` (on Windows, use WSL).

## Deploy

```bash
export FLY_API_TOKEN=...          # Fly deploy token
export NEON_DATABASE_URL=...      # Neon connection string
export DEMO_ADMIN_EMAIL=...       # the first dashboard account
export DEMO_ADMIN_PASSWORD=...    # at least 12 characters
scripts/deploy-fly.sh
```

The script:

1. Creates both Fly apps if they do not exist (`FLY_ORG`, `FLY_APP` and `FLY_SIMULATOR_APP` override the defaults).
2. Converts the Neon URL to a JDBC URL with `sslmode=require` and stores the database credentials as Fly secrets, without printing them.
3. Generates `SECRET_ENCRYPTION_KEY` (32 random bytes), `METRICS_TOKEN` and the simulator's `SIMULATOR_CONTROL_TOKEN`, **only if they do not exist yet**. Re-running never replaces them: a new encryption key would make every stored signing secret unreadable. The simulator token is also saved to `~/.config/webhook-demo/simulator-token` (mode 600) so `demo.sh` can drive the public simulator.
4. Builds both images on Fly's remote builders from the same `Dockerfile` (`--build-target app` and `--build-target simulator`) and deploys them.

The platform is then at `https://webhook-platform-demo.fly.dev` and the simulator at `https://webhook-simulator-demo.fly.dev`. Endpoints for the public simulator use its public `https://` URL; the production configuration has no SSRF allowlist, and none is needed.

## Trusted proxies

Fly's proxy terminates TLS and forwards requests to the machine, so the app must know which peer addresses are Fly's proxy before it believes `X-Forwarded-For` (see [SECURITY.md](SECURITY.md#reverse-proxies-and-client-addresses)). Fly does not document that range, so it is observed rather than assumed:

1. **First deploy without trust.** Leave `TRUSTED_PROXIES` unset. The script then sets `PROXY_DIAGNOSTICS=true`, which logs each request's peer and forwarding headers without trusting them.
2. **Observe.** Make a few requests from a known address, including one with a spoofed header, and read the logs:
   ```bash
   curl -s https://webhook-platform-demo.fly.dev/actuator/health
   curl -s -H "X-Forwarded-For: 203.0.113.9" https://webhook-platform-demo.fly.dev/actuator/health
   fly logs -a webhook-platform-demo | grep "proxy diagnostics"
   ```
   Check that the logged peer addresses all fall in one private range (a community report says `172.16.0.0/16`), and that the proxy appended your real address as the **last** `X-Forwarded-For` entry after the spoofed one.
3. **Trust exactly that range** and turn diagnostics off:
   ```bash
   TRUSTED_PROXIES=172.16.0.0/16 scripts/deploy-fly.sh
   ```
   Never trust Fly's private IPv6 network (`fdaa::/16`): other machines in the organisation can connect from it directly and would be able to forge client addresses.
4. **Verify.** Trigger the login limit with a spoofed `X-Forwarded-For` that changes on every request: it must still return `429` after 5 attempts.

Until step 3 is done, every visitor shares one login allowance of 5 attempts a minute (the proxy's address), and HSTS is not sent. Logins still work, but the demo should not be announced before then.

## Configuration reference

| Variable | Set by | Purpose |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | script (secret) | Neon connection. |
| `DATABASE_POOL_SIZE` | `app.toml` (`10`) | Connections per instance; keep well under the Neon plan's limit. |
| `SECRET_ENCRYPTION_KEY` | script, once (secret) | AES-256-GCM key for signing secrets at rest. Back it up; losing it makes every endpoint's secret unreadable. |
| `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD` | script (secret) | The first dashboard account, created if missing. |
| `METRICS_TOKEN` | script, once (secret) | Bearer token for `/actuator/prometheus`. |
| `TRUSTED_PROXIES`, `PROXY_DIAGNOSTICS` | script (env) | See above. |
| `LOG_FORMAT` | `app.toml` (`ecs`) | One JSON object per log line. |
| `SIMULATOR_CONTROL_TOKEN` | script, once (simulator secret) | Protects the public simulator's control API. |

Never deploy with `SPRING_PROFILES_ACTIVE=local`: that profile has public development secrets and an SSRF allowlist.

## Operating it

- **Health:** `/actuator/health` (Fly checks it every 15 s after a 60 s grace period).
- **Metrics:** `curl -H "Authorization: Bearer $METRICS_TOKEN" https://webhook-platform-demo.fly.dev/actuator/prometheus`. The key alert is `webhooks_queue_oldest_due_seconds`: a growing value means the worker is stuck or undersized. Metric definitions are in [ARCHITECTURE.md](ARCHITECTURE.md#observability). Read the token with `fly ssh console -a webhook-platform-demo -C 'printenv METRICS_TOKEN'`; it is never printed by the script.
- **Logs:** `fly logs -a webhook-platform-demo`. Every delivery log line carries `deliveryId`, `eventId`, `endpointId` and `attempt`; secret-shaped values are masked.
- **Database backups:** Neon keeps point-in-time history (up to 7 days on Launch).
- **Scaling out:** more machines work (the queue uses `FOR UPDATE SKIP LOCKED`, leases and fencing), but rate limits are per instance and the pool size multiplies with the machine count.
- **Rolling back:** `fly releases -a webhook-platform-demo`, then `fly deploy --image <previous image>`. Database migrations are forward-only, so check that the older release understands the current schema first.
