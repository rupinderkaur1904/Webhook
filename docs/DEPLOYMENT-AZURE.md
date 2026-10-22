# Deployment on Azure for Students (₹0)

A private, portfolio-ready Relay on one small Azure VM, with Azure Database for PostgreSQL and HTTPS from Caddy. It is sized for a demo with one owner account, not for real customer webhooks: see [What this is not](#what-this-is-not).

The paid alternative (Fly.io + Neon, about US$30 a month) is in [DEPLOYMENT.md](DEPLOYMENT.md). Local development keeps using the root `docker-compose.yml` ([LOCAL-TESTING.md](LOCAL-TESTING.md)).

## Topology

```
visitor ──HTTPS──▶ Caddy :443 ──▶ platform :8080 ──TLS──▶ Azure PostgreSQL (private subnet)
                   (VM, Docker)      │
                                     └──▶ receivers: public HTTP/HTTPS only (egress firewall)
                   simulator :9090 on 127.0.0.1 only (optional, --profile demo)
```

| Piece | Where | Notes |
|---|---|---|
| Caddy | VM, `caddy:2-alpine` by digest | Let's Encrypt certificate for the VM's Azure DNS name; HTTP → HTTPS; `/actuator/prometheus` answers 404 |
| Platform | VM, `ghcr.io/<owner>/webhook-platform@sha256:…` | API, dashboard and delivery worker in one container; 384 MB heap; no host port |
| Simulator (optional) | VM, `ghcr.io/<owner>/webhook-simulator@sha256:…` | Loopback port only; control token required |
| PostgreSQL 16 | Azure Database for PostgreSQL Flexible Server, Burstable B1MS | Private access in the VNet; automatic backups (7 days) |
| Egress firewall | VM kernel (`DOCKER-USER` and `INPUT` chains) | See [Egress firewall](#egress-firewall) |

Files: [`deploy/azure/`](../deploy/azure/) holds `compose.yml`, `Caddyfile`, `.env.example`, `new-secrets.sh`, `egress-firewall.sh`, `relay-egress.service`, `verify.sh`, `demo-timings.env.example` and a local rehearsal (`compose.rehearsal.yml`).

## Cost: staying at ₹0

Azure for Students gives US$100 of credit for 12 months without a card, plus 12 months of free amounts (checked 21 Oct 2026 on https://azure.microsoft.com/en-in/free/students/). When the credit runs out the subscription is disabled; nothing is charged to you. Stay inside the free amounts:

- **One** VM of a size the offer lists as free (B1s, B2pts v2 or B2ats v2; pick an x86 one, **B1s or B2ats v2**, because the images are built for `linux/amd64`). 750 hours covers one VM all month. A second running VM, or any other size, spends credit.
- **One** PostgreSQL Flexible Server, **Burstable B1MS**, **32 GB** storage, high availability **off**, geo-redundant backup **off**.
- OS disk no larger than the free managed disk allowance (two 64 GB P6 SSDs).
- No Marketplace images, no Microsoft Defender plans, no Log Analytics workspace.

The Standard public IPv4 address and the private DNS zone for the database are probably not in the free amounts and are paid from the credit (roughly US$4–5 a month together; check the Azure pricing calculator). Watch **Cost Management → Credits** weekly for the first month. The free amounts end after 12 months: plan to shut down or move before then.

## 1. Azure resources (portal)

1. Activate **Azure for Students** with your college e-mail. In Cost Management, check the offer name and the credit. If budgets are available on your subscription, add a US$10/month budget with an e-mail alert.
2. **Resource group** `relay-rg` in a region your subscription allows (try Central India). Create everything below in that region.
3. **Virtual network** `relay-vnet`, `10.20.0.0/16`, with two subnets:
   - `vm` `10.20.1.0/24`;
   - `pg` `10.20.2.0/24`, delegated to `Microsoft.DBforPostgreSQL/flexibleServers`.
4. **Azure Database for PostgreSQL – Flexible Server**: PostgreSQL **16**, *Burstable, B1MS*, storage 32 GB, high availability off, backup retention 7 days, locally redundant. Networking: **Private access (VNet integration)** in `relay-vnet/pg`, with a new private DNS zone. Use a generated admin password and keep it in your password manager. Then:
   - *Server parameters* → `azure.extensions` → tick **CITEXT** → Save.
5. **Virtual machine**: Ubuntu Server 24.04 LTS (x64), size **B1s** or **B2ats v2**, authentication **SSH public key**, OS disk 64 GB or smaller, subnet `relay-vnet/vm`, new **Standard** public IP. Inbound ports: SSH only for now.
   - Public IP → *Configuration* → set a **DNS name label** (e.g. `relay-demo`). Your address is `relay-demo.<region>.cloudapp.azure.com`.
   - Network security group: allow **22 from your own IP only** (*My IP address*), allow **80 and 443 from Any**. Nothing else.

## 2. Prepare the VM

From Windows, `ssh azureuser@relay-demo.<region>.cloudapp.azure.com` works in PowerShell.

```bash
# Docker Engine and the Compose plugin (https://docs.docker.com/engine/install/ubuntu/)
sudo apt-get update && sudo apt-get install -y ca-certificates curl postgresql-client
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 2 GB swap: the VM has 1 GiB of memory
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# The deployment files (no secrets in them)
sudo mkdir -p /opt/relay && sudo chown "$USER" /opt/relay
git clone --depth 1 https://github.com/rupinderkaur1904/Webhook.git /tmp/relay-src
cp /tmp/relay-src/deploy/azure/* /tmp/relay-src/deploy/azure/.env.example /opt/relay/

# Egress firewall, now and at every boot
sudo cp /opt/relay/relay-egress.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now relay-egress
sudo /opt/relay/egress-firewall.sh --show | head
```

## 3. Database role

Create a role for the app that owns its database (the admin account stays unused by the app). `new-secrets.sh` generates `DATABASE_PASSWORD` for it in the next step, so run that first and read the value from `.env`:

```bash
cd /opt/relay && cp .env.example .env && ./new-secrets.sh
grep '^DATABASE_PASSWORD=' .env          # use this value below; it is not printed anywhere else
psql "host=<server>.postgres.database.azure.com user=<admin> dbname=postgres sslmode=require"
```
```sql
CREATE ROLE relay_app LOGIN PASSWORD '<DATABASE_PASSWORD from .env>';
CREATE DATABASE webhooks OWNER relay_app;
\c webhooks
CREATE EXTENSION IF NOT EXISTS citext;   -- needs CITEXT in azure.extensions (step 1.4)
```

The platform's Flyway migration also runs `CREATE EXTENSION IF NOT EXISTS citext`; creating it here as the admin avoids needing extra rights for `relay_app`.

## 4. Configure and start

Edit `/opt/relay/.env` (keep it at mode 600; it is never committed):

| Variable | Value |
|---|---|
| `RELAY_DOMAIN` | `relay-demo.<region>.cloudapp.azure.com` |
| `ACME_EMAIL` | your e-mail, for certificate notices |
| `RELAY_APP_IMAGE`, `RELAY_SIMULATOR_IMAGE` | the digests from the latest **Images** workflow run on `main` (run summary) |
| `DATABASE_URL` | `jdbc:postgresql://<server>.postgres.database.azure.com:5432/webhooks?sslmode=require` |
| `DATABASE_USERNAME` | `relay_app` |
| `DATABASE_POOL_SIZE` | `8` (B1MS allows few connections: `SHOW max_connections;`) |
| `BOOTSTRAP_EMAIL` | your sign-in e-mail |
| `BOOTSTRAP_API_KEY` | optional `whk_…` key (36+ characters) for `scripts/demo.sh` |
| `SECRET_ENCRYPTION_KEY`, `BOOTSTRAP_PASSWORD`, `DATABASE_PASSWORD`, `METRICS_TOKEN`, `SIMULATOR_CONTROL_TOKEN` | generated by `new-secrets.sh`, never replaced on re-runs |
| `SSRF_ALLOWED_HOSTS` | empty; `simulator:9090` only when you run the simulator |

Fixed in `compose.yml` and not configurable: `REGISTRATION_ENABLED=false`, `SESSION_COOKIE_SECURE=true`, `TRUSTED_PROXIES=172.30.0.2/32` (Caddy's pinned address), `LOG_FORMAT=ecs`. The `local` profile is never used.

**Back up `SECRET_ENCRYPTION_KEY`** in your password manager now. Without it, every endpoint's signing secret in the database is unreadable.

Images: the **Images** workflow publishes to GHCR from `main`. A new package may start as private; either make `webhook-platform` and `webhook-simulator` public (*Package settings → Change visibility*), or run `docker login ghcr.io` on the VM with a personal access token that has only `read:packages`.

```bash
cd /opt/relay
docker compose pull
docker compose up -d                  # Caddy + platform
docker compose ps                     # app: healthy; caddy: up
docker compose logs caddy | grep -i certificate
```

Sign in at `https://<RELAY_DOMAIN>/overview` with `BOOTSTRAP_EMAIL` and the `BOOTSTRAP_PASSWORD` from `.env`.

**Simulator for demos (optional):** set `SSRF_ALLOWED_HOSTS=simulator:9090`, then `docker compose --profile demo up -d`. Register endpoints with `http://simulator:9090/receivers/<name>`. From your PC, `ssh -L 9090:127.0.0.1:9090 azureuser@<host>` reaches its control API on `http://localhost:9090` with the `SIMULATOR_CONTROL_TOKEN` bearer token. Stop it with `docker compose --profile demo stop simulator` when you are not demonstrating.

**Demo timings (optional):** production retry timings take hours to show. `cp demo-timings.env.example demo-timings.env && docker compose up -d app` switches to the short local-profile timings; delete the file and run the same command to switch back.

## Egress firewall

The platform refuses private, loopback, link-local and reserved destinations itself ([SECURITY.md](SECURITY.md)). `egress-firewall.sh` is the second layer for what the platform allows, on the VM's kernel:

- traffic leaving the Compose subnet may go only to the Azure DNS resolver (port 53), the PostgreSQL subnet (5432), and public addresses on **TCP 80 and 443**;
- everything else is rejected: other ports, UDP, private and link-local ranges (including `169.254.169.254`), and the Azure platform address `168.63.129.16`, which is public and therefore not blocked by the platform;
- the containers cannot open connections to the VM itself (SSH or anything else listening on it).

`relay-egress.service` applies it at boot after Docker; `sudo ./egress-firewall.sh --show` prints the rules with counters, `--remove` takes them out. If you change the subnets, set `RELAY_SUBNET`, `DB_SUBNET` or `DNS_RESOLVER` in `/opt/relay/egress.env`.

## Verify

There are three levels of evidence; a green health check is only the first.

**Before deploying (on your PC): local rehearsal.** The same compose file with a throwaway database and Caddy's local certificate authority:

```bash
cd deploy/azure
docker build --target app -t relay-app:rehearsal ../..
docker build --target simulator -t relay-sim:rehearsal ../..
cp rehearsal.env.example rehearsal.env && ./new-secrets.sh rehearsal.env
docker compose --env-file rehearsal.env -f compose.yml -f compose.rehearsal.yml --profile demo up -d
set -a; . ./rehearsal.env; set +a
RELAY_URL=https://localhost:8443 INSECURE=1 DASHBOARD_EMAIL=$BOOTSTRAP_EMAIL DASHBOARD_PASSWORD=$BOOTSTRAP_PASSWORD ./verify.sh public
docker compose --env-file rehearsal.env -f compose.yml -f compose.rehearsal.yml --profile demo down -v
```

On Linux (or WSL) with root, `sudo ./egress-firewall.sh` and `COMPOSE_FILES="--env-file rehearsal.env -f compose.yml -f compose.rehearsal.yml --profile demo" ALLOW_LOCAL_IMAGES=1 sudo -E ./verify.sh host` exercise the firewall too.

**Provider level.** `docker compose ps` shows the app healthy; the Azure portal shows the VM running and the database available; `Test-NetConnection <host> -Port 5432` and `-Port 9090` from your PC fail.

**End to end in the cloud.** Each is repeatable:

1. `RELAY_URL=https://<RELAY_DOMAIN> DASHBOARD_EMAIL=… DASHBOARD_PASSWORD=… ./verify.sh public` from your PC (WSL or Git Bash): trusted certificate, HTTP → HTTPS, health, landing page, metrics not public, HSTS and CSP, registration off, 13 SSRF spellings refused at registration, login throttle holding against a spoofed `X-Forwarded-For`. The last check uses up your login allowance for a minute.
2. `sudo ./verify.sh host` on the VM: only 22/80/443 listen publicly, no PostgreSQL on the VM, simulator on loopback, firewall installed, the app cannot reach metadata, the Azure platform address, private ranges, the VM itself or non-HTTP ports, but reaches public HTTPS and the database; every container runs a digest-pinned image.
3. Delivery scenarios, with the simulator and demo timings on, from the VM (`git clone` the repository for `scripts/demo.sh`):
   ```bash
   set -a; . /opt/relay/.env; set +a
   export API_URL=https://$RELAY_DOMAIN SIMULATOR_URL=http://127.0.0.1:9090 SIMULATOR_URL_FROM_API=http://simulator:9090 \
          API_KEY=$BOOTSTRAP_API_KEY DASHBOARD_EMAIL=$BOOTSTRAP_EMAIL DASHBOARD_PASSWORD=$BOOTSTRAP_PASSWORD
   for s in success signature recover replay breaker; do scripts/demo.sh $s; done
   CRASH_CMD="docker compose --project-directory /opt/relay kill app" \
   START_CMD="docker compose --project-directory /opt/relay start app" scripts/demo.sh crash
   ```
   The breaker scenario's last step reads Prometheus through the public URL and prints nothing, because Caddy keeps metrics private.
4. **Durability across restarts:** set a receiver to answer 503, queue 10 events, `sudo reboot`, set the receiver to 200 after the VM is back: all 10 deliveries end `DELIVERED` with their earlier attempts in the history.
5. **A real receiver:** register an endpoint on a public HTTPS receiver you control, send a test event from the dashboard, and verify the signature with one of the examples in `examples/receivers`.
6. **Backup:** in the portal, *Restore* the database to a new server at a recent point in time, check `SELECT count(*) FROM events;`, then delete the restored server (it costs credit while it exists).

## Operating it

- **Logs:** `docker compose logs -f app` (one JSON object per line; secrets masked).
- **Metrics** (not exposed publicly): `docker compose exec app sh -c 'wget -qO- --header "Authorization: Bearer $METRICS_TOKEN" http://127.0.0.1:8080/actuator/prometheus' | grep webhooks_queue_oldest_due_seconds`. A growing value means the worker is stuck.
- **Uptime:** any free external monitor on `https://<RELAY_DOMAIN>/actuator/health`.
- **Updating:** put the new digests from the **Images** run in `.env`, then `docker compose pull && docker compose up -d`. Migrations run at start-up and are forward-only.
- **Rolling back:** put the previous digests back and `docker compose up -d`, after checking that the older release understands the current schema.
- **Pausing (saves credit):** stop the VM from the portal (*Stop*, which deallocates it) and stop the PostgreSQL server. Data is kept. Azure starts a stopped Flexible Server again automatically after 7 days.
- **Shutting down:** `pg_dump` the database to your PC if you want to keep it, keep the encryption key, then delete the resource group `relay-rg`.

## What this is not

This setup is for a private or owner-only portfolio demo:

- One account. Do not enable registration or share a demo login: anyone signed in can make the server send signed requests to any public HTTPS URL.
- One VM without high availability; the free amounts end after 12 months.
- Endpoint edits have no optimistic concurrency, and a second secret rotation within the grace period ends the first one's grace early (documented limitations, not deployment blockers for one user).
- Delivery is at least once: receivers must deduplicate on `webhook-id`.
