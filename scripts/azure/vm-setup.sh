#!/usr/bin/env bash
# Runs on the Azure VM as root (deploy-azure.ps1 calls it over SSH). Installs Docker, the egress
# firewall and Relay, creates the database role, starts the stack and runs verify.sh.
#   sudo bash vm-setup.sh <relay-domain> <email> <postgres-host>   (PostgreSQL admin password on stdin)
# Safe to re-run: existing secrets in /opt/relay/.env are kept.
set -euo pipefail
DOMAIN="$1" EMAIL="$2" PGHOST="$3"
IFS= read -r PGADMIN_PW
APP_IMAGE=ghcr.io/rupinderkaur1904/webhook-platform@sha256:11b777e74f0516d70c3457797c47edc27e1384b1762b30f25db2ccd8eccfcdbb
SIM_IMAGE=ghcr.io/rupinderkaur1904/webhook-simulator@sha256:0a0d834b447db3432ab91068393e16c8e6c6a337ca3bdcc8a5fa4c68aa44e684
step() { printf '\n==== %s\n' "$*"; }

step "Docker, PostgreSQL client, git"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q ca-certificates curl git postgresql-client iproute2 netcat-openbsd >/dev/null
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
fi
docker --version; docker compose version

step "2 GB swap"
if [[ ! -f /swapfile ]]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
free -m | head -3

step "Deployment files into /opt/relay"
rm -rf /tmp/relay-src
git clone -q --depth 1 https://github.com/rupinderkaur1904/Webhook.git /tmp/relay-src
mkdir -p /opt/relay
cp /tmp/relay-src/deploy/azure/* /tmp/relay-src/deploy/azure/.env.example /opt/relay/
chmod +x /opt/relay/*.sh

step "Egress firewall service"
cp /opt/relay/relay-egress.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now relay-egress
/opt/relay/egress-firewall.sh --show | head -20 || true  # head closes the pipe early; pipefail would stop here

step "Secrets and configuration"
cd /opt/relay
[[ -f .env ]] || cp .env.example .env
./new-secrets.sh .env
setenv() {  # setenv NAME VALUE: replace the line in .env (values here contain no newlines)
  local tmp; tmp="$(mktemp .env.XXXXXX)"
  awk -v n="$1" -v v="$2" 'BEGIN{FS=OFS="="} $1==n {print n "=" v; f=1; next} {print} END{if(!f) print n "=" v}' .env > "$tmp"
  mv "$tmp" .env; chmod 600 .env
}
setenv RELAY_DOMAIN "$DOMAIN"
setenv ACME_EMAIL "$EMAIL"
setenv BOOTSTRAP_EMAIL "$EMAIL"
setenv RELAY_APP_IMAGE "$APP_IMAGE"
setenv RELAY_SIMULATOR_IMAGE "$SIM_IMAGE"
setenv DATABASE_URL "jdbc:postgresql://$PGHOST:5432/webhooks?sslmode=require"
setenv DATABASE_USERNAME relay_app
setenv DATABASE_POOL_SIZE 8
setenv SSRF_ALLOWED_HOSTS ""
echo "configured RELAY_DOMAIN=$DOMAIN, DATABASE host $PGHOST"

step "Database role and database"
DB_PW="$(grep -E '^DATABASE_PASSWORD=' .env | cut -d= -f2-)"
export PGPASSWORD="$PGADMIN_PW" PGSSLMODE=require PGCONNECT_TIMEOUT=15
psql -X -q -v ON_ERROR_STOP=1 -v pw="$DB_PW" "host=$PGHOST user=relayadmin dbname=postgres" <<'SQL'
SELECT 'CREATE ROLE relay_app LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'relay_app') \gexec
ALTER ROLE relay_app LOGIN PASSWORD :'pw';
GRANT relay_app TO CURRENT_USER WITH SET TRUE;
SELECT 'CREATE DATABASE webhooks OWNER relay_app' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'webhooks') \gexec
SQL
psql -X -q -v ON_ERROR_STOP=1 "host=$PGHOST user=relayadmin dbname=webhooks" -c 'CREATE EXTENSION IF NOT EXISTS citext;'
unset PGPASSWORD PGADMIN_PW DB_PW
echo "relay_app and webhooks ready"

step "Pull images (they must be public on GHCR)"
for i in $(seq 1 10); do
  if docker compose pull caddy app; then break; fi
  [[ $i == 10 ]] && { echo "Image pull still denied after 20 minutes: make both GHCR packages public, then re-run."; exit 1; }
  echo "pull failed ($i/10), retrying in 2 minutes"; sleep 120
done

step "Start Caddy and the platform"
docker compose up -d
for i in $(seq 1 60); do
  state="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || echo starting)"
  [[ "$state" == healthy ]] && break
  sleep 5
done
docker compose ps
[[ "$state" == healthy ]] || { echo "App not healthy after 5 minutes:"; docker compose logs --tail 80 app; exit 1; }
for i in $(seq 1 24); do
  docker compose logs caddy 2>&1 | grep -qi 'certificate obtained successfully' && break
  sleep 5
done
docker compose logs caddy 2>&1 | grep -iE 'certificate obtained|error' | tail -5 || true

step "verify.sh host"
./verify.sh host || true

step "verify.sh public"
( set -a; . ./.env; set +a
  RELAY_URL="https://$RELAY_DOMAIN" DASHBOARD_EMAIL="$BOOTSTRAP_EMAIL" DASHBOARD_PASSWORD="$BOOTSTRAP_PASSWORD" ./verify.sh public ) || true

step "Done: https://$DOMAIN"
