#!/usr/bin/env bash
# Fills the empty secret values in .env with fresh random ones. Values that are already set are never
# replaced: a new SECRET_ENCRYPTION_KEY would make every stored signing secret unreadable.
#
#   ./new-secrets.sh [path/to/.env]      # default: .env next to this script
#
# Secrets are written straight to the file (mode 600) and never printed or passed as arguments. Read
# BOOTSTRAP_PASSWORD and DATABASE_PASSWORD from the file, and copy SECRET_ENCRYPTION_KEY to your
# password manager: it is the one value that cannot be recovered.
set -euo pipefail

ENV_FILE="${1:-$(dirname "$0")/.env}"
[[ -f "$ENV_FILE" ]] || { echo "No $ENV_FILE: copy .env.example to .env first." >&2; exit 1; }
umask 077
chmod 600 "$ENV_FILE"

# name generator: base64 for the 32-byte AES key, URL-safe text for the rest.
key32() { openssl rand -base64 32; }
token() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-"$1"; }

fill() {
  local name="$1" value="$2" current
  current="$(grep -E "^${name}=" "$ENV_FILE" | tail -n1 | cut -d= -f2- || true)"
  if [[ -n "$current" ]]; then
    echo "kept       $name"
    return
  fi
  local tmp
  tmp="$(mktemp "${ENV_FILE}.XXXXXX")"
  if grep -qE "^${name}=" "$ENV_FILE"; then
    awk -v n="$name" -v v="$value" 'BEGIN { FS = OFS = "=" } $1 == n { print n "=" v; next } { print }' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    printf '%s=%s\n' "$name" "$value" >> "$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
  echo "generated  $name"
}

fill SECRET_ENCRYPTION_KEY "$(key32)"
fill BOOTSTRAP_PASSWORD "$(token 24)"
fill DATABASE_PASSWORD "$(token 32)"
fill METRICS_TOKEN "$(token 40)"
fill SIMULATOR_CONTROL_TOKEN "$(token 40)"
echo "Done. Secrets are in $ENV_FILE (mode 600); nothing was printed."
