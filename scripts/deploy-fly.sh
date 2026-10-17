#!/usr/bin/env bash
# Deploys the receiver simulator and the platform to Fly.io (docs/DEPLOYMENT.md).
#
# Needs flyctl and, in the environment:
#   FLY_API_TOKEN        deploy token for the Fly organization
#   NEON_DATABASE_URL    Neon connection string (postgresql://user:password@host/db?sslmode=require)
#   DEMO_ADMIN_EMAIL     dashboard sign-in for the bootstrap account
#   DEMO_ADMIN_PASSWORD  at least 12 characters
# Optional: FLY_ORG (default personal), FLY_APP, FLY_SIMULATOR_APP, TRUSTED_PROXIES.
#
# Generated secrets (encryption key, metrics token, simulator control token) are created once and
# stored only in Fly; re-running never replaces them, because a new encryption key would make every
# stored signing secret unreadable. The simulator token is also written to
# ~/.config/webhook-demo/simulator-token (mode 600) so demo.sh can drive the public simulator.
set -euo pipefail
cd "$(dirname "$0")/.."

for var in FLY_API_TOKEN NEON_DATABASE_URL DEMO_ADMIN_EMAIL DEMO_ADMIN_PASSWORD; do
  [[ -n "${!var:-}" ]] || { echo "missing $var" >&2; exit 2; }
done
(( ${#DEMO_ADMIN_PASSWORD} >= 12 )) || { echo "DEMO_ADMIN_PASSWORD must be at least 12 characters" >&2; exit 2; }
command -v fly >/dev/null || { echo "flyctl not found (https://fly.io/docs/flyctl/install/)" >&2; exit 2; }

ORG="${FLY_ORG:-personal}"
APP="${FLY_APP:-webhook-platform-demo}"
SIM="${FLY_SIMULATOR_APP:-webhook-simulator-demo}"

ensure_app() {
  fly apps list --json | jq -e --arg n "$1" '.[] | select(.Name == $n)' >/dev/null || fly apps create "$1" --org "$ORG"
}

has_secret() {
  fly secrets list -a "$1" --json | jq -e --arg n "$2" '.[] | select(.Name == $n or .name == $n)' >/dev/null
}

# postgresql://user:password@host[:port]/db?params -> JDBC URL, user and password, without echoing them.
{ IFS= read -r JDBC_URL; IFS= read -r DB_USER; IFS= read -r DB_PASSWORD; } < <(python3 - <<'PY'
import os, urllib.parse as u
p = u.urlsplit(os.environ["NEON_DATABASE_URL"])
assert p.scheme in ("postgres", "postgresql") and p.hostname and p.path.strip("/"), "unexpected NEON_DATABASE_URL"
query = dict(u.parse_qsl(p.query))
query["sslmode"] = query.get("sslmode", "require")
query.pop("channel_binding", None)  # libpq-only parameter
host = p.hostname + (f":{p.port}" if p.port else "")
print(f"jdbc:postgresql://{host}{p.path}?{u.urlencode(query)}", u.unquote(p.username or ""), u.unquote(p.password or ""), sep="\n")
PY
)

ensure_app "$SIM"
ensure_app "$APP"

# ---- Simulator --------------------------------------------------------------------------------------
TOKEN_FILE="$HOME/.config/webhook-demo/simulator-token"
if ! has_secret "$SIM" SIMULATOR_CONTROL_TOKEN; then
  mkdir -p "$(dirname "$TOKEN_FILE")"
  (umask 077; openssl rand -hex 32 > "$TOKEN_FILE")
  fly secrets set -a "$SIM" --stage SIMULATOR_CONTROL_TOKEN="$(cat "$TOKEN_FILE")" >/dev/null
  echo "simulator control token stored in Fly and in $TOKEN_FILE"
fi
fly deploy . --config deploy/fly/simulator.toml --dockerfile Dockerfile --build-target simulator \
  --app "$SIM" --ha=false --remote-only

# ---- Platform -------------------------------------------------------------------------------------
secrets=(DATABASE_URL="$JDBC_URL" DATABASE_USERNAME="$DB_USER" DATABASE_PASSWORD="$DB_PASSWORD"
         BOOTSTRAP_EMAIL="$DEMO_ADMIN_EMAIL" BOOTSTRAP_PASSWORD="$DEMO_ADMIN_PASSWORD")
has_secret "$APP" SECRET_ENCRYPTION_KEY || secrets+=(SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)")
has_secret "$APP" METRICS_TOKEN || secrets+=(METRICS_TOKEN="$(openssl rand -hex 32)")
fly secrets set -a "$APP" --stage "${secrets[@]}" >/dev/null

# Until the proxy's source range has been observed, trust no forwarding headers and log peers instead.
env_flags=()
if [[ -n "${TRUSTED_PROXIES:-}" ]]; then
  env_flags+=(--env "TRUSTED_PROXIES=$TRUSTED_PROXIES" --env "PROXY_DIAGNOSTICS=false")
else
  env_flags+=(--env "PROXY_DIAGNOSTICS=true")
fi
fly deploy . --config deploy/fly/app.toml --dockerfile Dockerfile --build-target app \
  --app "$APP" --ha=false --remote-only "${env_flags[@]}"

echo "platform:  https://$APP.fly.dev"
echo "simulator: https://$SIM.fly.dev"
