#!/usr/bin/env bash
# Repeatable checks for a Relay deployment (docs/DEPLOYMENT-AZURE.md, "Verify"). Each check prints
# PASS, FAIL or SKIP; the exit status is the number of failures.
#
#   RELAY_URL=https://<domain> ./verify.sh public
#       From any machine with bash and curl. HTTPS, health, closed endpoints, security headers, SSRF
#       rejection (with DASHBOARD_EMAIL and DASHBOARD_PASSWORD) and the login throttle with spoofed
#       X-Forwarded-For. The throttle check uses up this machine's login allowance for a minute.
#
#   sudo ./verify.sh host
#       On the VM, from this directory. Listening ports, the egress firewall (probed from inside the app
#       container), image digests and memory use.
#
# Options (environment): INSECURE=1 accepts a self-signed certificate (local rehearsal only);
# SKIP_INTERNET=1 skips the check that public HTTPS is reachable from the app (sandboxes without internet);
# ALLOW_LOCAL_IMAGES=1 accepts locally built images (rehearsal); COMPOSE_FILES passes extra compose
# arguments (rehearsal); HOST_PROBE_PORT adds another VM port the containers must not reach.
set -uo pipefail

failures=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }
skip() { printf '  SKIP  %s\n' "$1"; }
check() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }

public_checks() {
  local url="${RELAY_URL:?set RELAY_URL, e.g. https://relay-demo.centralindia.cloudapp.azure.com}"
  url="${url%/}"
  local tls=(); [[ "${INSECURE:-0}" == 1 ]] && tls=(-k)
  local jar; jar="$(mktemp)"; trap 'rm -f "$jar"' RETURN
  c() { curl -sS "${tls[@]}" --max-time 20 "$@"; }
  status() { c -o /dev/null -w '%{http_code}' "$@"; }
  xsrf() { awk '$6 == "XSRF-TOKEN" {print $7}' "$jar" | tail -n1; }
  csrf() { c -c "$jar" -b "$jar" -o /dev/null "$url/api/v1/auth/csrf"; }
  json_post() { local path="$1" body="$2"; shift 2
    c -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" -H 'Content-Type: application/json' \
      -H "X-XSRF-TOKEN: $(xsrf)" "$@" -X POST "$url$path" -d "$body"; }

  echo "Public checks against $url"
  if [[ "${INSECURE:-0}" == 1 ]]; then
    skip "certificate is trusted (INSECURE=1)"
  else
    check "certificate is trusted" curl -sS -o /dev/null --max-time 20 "$url/actuator/health"
  fi
  check "health is UP" bash -c "curl -sS ${tls[*]} --max-time 20 '$url/actuator/health' | grep -q '\"status\":\"UP\"'"

  local host="${url#https://}"; host="${host%%/*}"
  if [[ "${INSECURE:-0}" == 1 ]]; then
    skip "HTTP redirects to HTTPS (rehearsal publishes no port 80)"
  else
    local loc; loc="$(curl -sS -o /dev/null -w '%{redirect_url}' --max-time 20 "http://$host/")"
    check "HTTP redirects to HTTPS" test "${loc#https://}" != "$loc"
  fi

  check "landing page is served" bash -c "curl -sS ${tls[*]} --max-time 20 '$url/' | grep -q '<div id=\"root\">'"
  check "metrics are not public (404)" test "$(status "$url/actuator/prometheus")" = 404

  local headers; headers="$(c -D - -o /dev/null "$url/api/v1/auth/csrf")"
  check "HSTS header (forwarded HTTPS trusted from Caddy)" grep -qi '^strict-transport-security:' <<<"$headers"
  check "Content-Security-Policy header" grep -qi '^content-security-policy:' <<<"$headers"
  check "no Server header" bash -c "! grep -qi '^server:' <<<'$headers'"

  csrf
  check "self-registration is off (404)" test "$(json_post /api/v1/auth/register '{"email":"probe@example.com","password":"probe-password-123"}')" = 404

  if [[ -n "${DASHBOARD_EMAIL:-}" && -n "${DASHBOARD_PASSWORD:-}" ]]; then
    local login; login="$(json_post /api/v1/auth/login "{\"email\":\"$DASHBOARD_EMAIL\",\"password\":\"$DASHBOARD_PASSWORD\"}")"
    if [[ "$login" == 200 || "$login" == 204 ]]; then
      csrf
      local bad
      for bad in 'http://169.254.169.254/latest/meta-data/' 'https://169.254.169.254/' 'https://localhost/' \
          'https://127.1/' 'https://0x7f000001/' 'https://10.20.2.4/' 'https://172.30.0.10:8080/' \
          'https://192.168.1.1/' 'https://[::1]/' 'https://[::ffff:10.0.0.1]/' 'https://[fd00:ec2::254]/' \
          'https://100.100.100.200/' 'http://example.com/'; do
        local code; code="$(json_post /api/v1/endpoints "{\"name\":\"verify-ssrf\",\"url\":\"$bad\"}")"
        check "registration refuses $bad ($code)" test "$code" = 400 -o "$code" = 422
      done
      json_post /api/v1/auth/logout '{}' >/dev/null
    else
      fail "dashboard sign-in for the SSRF checks (HTTP $login)"
    fi
  else
    skip "SSRF registration checks (set DASHBOARD_EMAIL and DASHBOARD_PASSWORD)"
  fi

  # Five attempts a minute per client address. A spoofed X-Forwarded-For that changes every time must
  # not reset it: Caddy replaces the header and the platform trusts it only from Caddy.
  csrf
  local i code got429=0
  for i in 1 2 3 4 5 6 7; do
    code="$(json_post /api/v1/auth/login "{\"email\":\"nobody-$RANDOM-$i@example.com\",\"password\":\"wrong-password-$i\"}" \
      -H "X-Forwarded-For: 203.0.113.$i")"
    [[ "$code" == 429 ]] && { got429=1; break; }
  done
  check "login throttle holds with a spoofed X-Forwarded-For (429 after $i attempts)" test "$got429" = 1
}

host_checks() {
  [[ $EUID -eq 0 ]] || { echo "Run as root (sudo) for the host checks." >&2; exit 1; }
  local here; here="$(cd "$(dirname "$0")" && pwd)"
  local dc=(docker compose --project-directory "$here")
  local extra=(); read -ra extra <<<"${COMPOSE_FILES:-}"
  dc+=("${extra[@]}")
  echo "Host checks"

  command -v ss >/dev/null || { echo "ss (iproute2) is required for the host checks." >&2; exit 1; }
  local listeners; listeners="$(ss -Htln | awk '{print $4}')"
  local exposed; exposed="$(grep -vE '^(127\.0\.0\.1|\[::1\]|127\.0\.0\.53%lo|127\.0\.0\.54):' <<<"$listeners" \
    | grep -vE ':(22|80|443)$' || true)"
  check "only 22, 80 and 443 listen on public interfaces${exposed:+ (also: $(tr '\n' ' ' <<<"$exposed"))}" test -z "$exposed"
  check "PostgreSQL is not listening on the VM" bash -c "! grep -qE ':5432$' <<<'$listeners'"
  if grep -qE ':9090$' <<<"$listeners"; then
    check "simulator is on loopback only" bash -c "grep -E ':9090$' <<<'$listeners' | grep -qvE '^(127\.0\.0\.1|\[::1\]):' && exit 1 || exit 0"
  else
    skip "simulator not running"
  fi

  check "egress chain is hooked into DOCKER-USER" bash -c "iptables -w -S DOCKER-USER | grep -q -- '-j RELAY-EGRESS'"
  check "host chain is hooked into INPUT" bash -c "iptables -w -S INPUT | grep -q -- '-j RELAY-HOST'"

  local app; app="$("${dc[@]}" ps -q app)"
  if [[ -z "$app" ]]; then fail "app container is running"; return; fi
  probe() { docker exec "$app" nc -z -w 3 "$1" "$2" >/dev/null 2>&1; }
  local target
  # 172.30.0.1 is the VM itself, seen from the Compose network (port 22: its SSH daemon).
  for target in 169.254.169.254:80 168.63.129.16:80 168.63.129.16:32526 10.0.0.1:22 192.168.1.1:80 \
      172.30.0.1:22 172.30.0.1:${HOST_PROBE_PORT:-22} 1.1.1.1:22 1.1.1.1:25 8.8.8.8:5432; do
    check "app cannot reach $target" bash -c "! docker exec '$app' nc -z -w 3 ${target%:*} ${target##*:} >/dev/null 2>&1"
  done
  if [[ "${SKIP_INTERNET:-0}" == 1 ]]; then
    skip "app reaches public HTTPS (SKIP_INTERNET=1)"
  else
    check "app reaches public HTTPS (1.1.1.1:443)" probe 1.1.1.1 443
  fi
  local dbhost; dbhost="$(docker exec "$app" printenv DATABASE_URL | sed -E 's#^jdbc:postgresql://([^:/?]+).*#\1#')"
  check "app reaches the database ($dbhost:5432)" probe "$dbhost" 5432

  local svc ref
  for svc in caddy app simulator; do
    local id; id="$("${dc[@]}" ps -q "$svc")"
    [[ -z "$id" ]] && continue
    ref="$(docker inspect -f '{{.Config.Image}}' "$id")"
    if [[ "$ref" == *@sha256:* ]]; then pass "$svc runs a digest-pinned image"
    elif [[ "${ALLOW_LOCAL_IMAGES:-0}" == 1 ]]; then skip "$svc image is a local build ($ref, ALLOW_LOCAL_IMAGES=1)"
    else fail "$svc image is not pinned by digest ($ref)"; fi
  done
  echo "  INFO  memory: $(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | tr '\n' ';')"
}

case "${1:-}" in
  public) public_checks ;;
  host) host_checks ;;
  *) echo "usage: $0 public|host" >&2; exit 2 ;;
esac
echo "$failures failure(s)."
exit "$failures"
