#!/usr/bin/env bash
# Demonstrations against a running local stack (backend :8080 with SPRING_PROFILES_ACTIVE=local,
# simulator :9090). The local profile retries quickly: 4 attempts, 2s base delay.
#
# Usage: scripts/demo.sh <scenario>
#   success     receiver answers 200: delivered on the first attempt, signature verified
#   recover     receiver fails with 500, then recovers: retries until delivered
#   exhaust     receiver always fails: retries until the budget is used, then dead-lettered
#   rate-limit  receiver answers 429 with Retry-After: 5, then 200: the retry waits >= 5s
#   permanent   receiver answers 400: dead-lettered immediately, no retries
#   idempotency same Idempotency-Key twice (200 replay), then with a different payload (409)
set -euo pipefail

API="${API_URL:-http://localhost:8080}"
SIM="${SIMULATOR_URL:-http://localhost:9090}"
SIM_FROM_API="${SIMULATOR_URL_FROM_API:-http://localhost:9090}"   # how the backend reaches the simulator
KEY="${API_KEY:-whk_local_dev_key_0000000000000000000000}"
SCENARIO="${1:-success}"
RUN="$(date +%s)-$RANDOM"
RECEIVER="demo-$SCENARIO-$RUN"

auth=(-H "Authorization: Bearer $KEY")
json=(-H "Content-Type: application/json")
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

receiver() {  # receiver <status> [headers-json]
  local headers="${2:-}"
  [[ -n "$headers" ]] || headers='{}'
  curl -sf "${json[@]}" -X PUT "$SIM/receivers/$RECEIVER/config" \
    -d "{\"status\":$1,\"delayMs\":0,\"secret\":\"$SECRET\",\"headers\":$headers}" >/dev/null
  echo "simulator now answers $1 ${2:-}"
}

delivery() { curl -sf "${auth[@]}" "$API/api/v1/deliveries/$DELIVERY"; }

wait_until() {  # wait_until <jq-condition> <timeout-seconds>
  local deadline=$((SECONDS + $2))
  until delivery | jq -e "$1" >/dev/null; do
    (( SECONDS < deadline )) || { echo "timed out waiting for: $1" >&2; exit 1; }
    sleep 0.5
  done
}

show() {
  step "Delivery state and attempt history"
  delivery | jq '{status: .delivery.status, attempts: "\(.delivery.attemptCount)/\(.delivery.maxAttempts)",
    nextAttemptAt: .delivery.nextAttemptAt, deadLetterReason: .delivery.deadLetterReason,
    history: [.attempts[] | {n: .attemptNumber, startedAt, outcome, http: .responseStatus, retryable, retryAfterSeconds, ms: .durationMs}]}'
  step "Receiver's view (simulator request log)"
  curl -sf "$SIM/receivers/$RECEIVER/requests" |
    jq '[.[] | {at: .receivedAt, attempt, signatureResult, duplicateWebhookId: .duplicate, respondedWith}]'
}

submit() {  # submit <idempotency-key> <payload-json>; prints "<http status> <body>"
  curl -s -w '\n%{http_code}' "${auth[@]}" "${json[@]}" -H "Idempotency-Key: $1" -X POST "$API/api/v1/events" \
    -d "{\"eventType\":\"order.created\",\"payload\":$2,\"endpointIds\":[\"$ENDPOINT\"]}"
}

step "Register endpoint for simulator receiver '$RECEIVER'"
created=$(curl -sf "${auth[@]}" "${json[@]}" -X POST "$API/api/v1/endpoints" \
  -d "{\"name\":\"$RECEIVER\",\"url\":\"$SIM_FROM_API/receivers/$RECEIVER\"}")
ENDPOINT=$(jq -r .endpoint.id <<<"$created")
SECRET=$(jq -r .secret <<<"$created")
echo "endpoint $ENDPOINT (signing secret ${SECRET:0:12}..., shown once; simulator verifies with it)"

case "$SCENARIO" in
  success)   receiver 200 ;;
  recover)   receiver 500 ;;
  exhaust)   receiver 503 ;;
  rate-limit) receiver 429 '{"Retry-After":"5"}' ;;
  permanent) receiver 400 ;;
  idempotency) receiver 200 ;;
  *) echo "unknown scenario: $SCENARIO" >&2; exit 2 ;;
esac

step "Submit event"
response=$(submit "order-$RUN" "{\"orderId\":\"ord_$RUN\",\"amount\":4999}")
echo "HTTP $(tail -n1 <<<"$response") (accepted before any delivery attempt)"
DELIVERY=$(sed '$d' <<<"$response" | jq -r '.deliveries[0].id')
echo "delivery $DELIVERY"

case "$SCENARIO" in
  success|idempotency)
    wait_until '.delivery.status == "DELIVERED"' 15 ;;
  recover)
    step "Wait for two failed attempts, then let the receiver recover"
    wait_until '.delivery.attemptCount >= 2 and .delivery.status == "RETRYING"' 30
    receiver 200
    wait_until '.delivery.status == "DELIVERED"' 30 ;;
  exhaust)
    step "Wait for the retry budget to run out (about 15s)"
    wait_until '.delivery.status == "DEAD_LETTERED"' 60 ;;
  rate-limit)
    wait_until '.delivery.status == "RETRYING"' 15
    receiver 200
    wait_until '.delivery.status == "DELIVERED"' 30 ;;
  permanent)
    wait_until '.delivery.status == "DEAD_LETTERED"' 15 ;;
esac
show

if [[ "$SCENARIO" == "idempotency" ]]; then
  step "Same Idempotency-Key, same request"
  r=$(submit "order-$RUN" "{\"orderId\":\"ord_$RUN\",\"amount\":4999}")
  echo "HTTP $(tail -n1 <<<"$r"), same event id: $(sed '$d' <<<"$r" | jq -r .id)"
  step "Same Idempotency-Key, different payload"
  r=$(submit "order-$RUN" '{"orderId":"something-else"}')
  echo "HTTP $(tail -n1 <<<"$r") $(sed '$d' <<<"$r" | jq -c '{code, detail}')"
fi
