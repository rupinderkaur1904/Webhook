#!/usr/bin/env bash
# Stage 1 demonstration against a running stack (backend :8080, simulator :9090).
#   Submit event -> persist -> schedule -> deliver to simulator -> persist result -> inspect result
# Usage: scripts/demo.sh [success|failure]
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
need() { command -v "$1" >/dev/null || { echo "missing dependency: $1" >&2; exit 1; }; }
need curl; need jq

step "Register endpoint -> simulator receiver '$RECEIVER'"
created=$(curl -sf "${auth[@]}" "${json[@]}" -X POST "$API/api/v1/endpoints" \
  -d "{\"name\":\"$RECEIVER\",\"url\":\"$SIM_FROM_API/receivers/$RECEIVER\"}")
endpoint_id=$(jq -r .endpoint.id <<<"$created")
secret=$(jq -r .secret <<<"$created")
echo "endpoint $endpoint_id (secret ${secret:0:12}..., shown once)"

status=200
[[ "$SCENARIO" == "failure" ]] && status=500
step "Configure simulator: respond $status and verify signatures with the endpoint secret"
curl -sf "${json[@]}" -X PUT "$SIM/receivers/$RECEIVER/config" \
  -d "{\"status\":$status,\"delayMs\":0,\"secret\":\"$secret\"}" | jq -c .

step "Submit event (Idempotency-Key: order-$RUN)"
accepted=$(curl -s -w '\n%{http_code}' "${auth[@]}" "${json[@]}" -H "Idempotency-Key: order-$RUN" \
  -X POST "$API/api/v1/events" \
  -d "{\"eventType\":\"order.created\",\"payload\":{\"orderId\":\"ord_$RUN\",\"amount\":4999},\"endpointIds\":[\"$endpoint_id\"]}")
echo "HTTP $(tail -n1 <<<"$accepted") (accepted before any delivery attempt)"
body=$(sed '$d' <<<"$accepted")
jq -c . <<<"$body"
delivery_id=$(jq -r '.deliveries[0].id' <<<"$body")

step "Wait for the background worker"
for _ in $(seq 1 40); do
  detail=$(curl -sf "${auth[@]}" "$API/api/v1/deliveries/$delivery_id")
  state=$(jq -r .delivery.status <<<"$detail")
  [[ "$state" == "DELIVERED" || "$state" == "DEAD_LETTERED" ]] && break
  sleep 0.25
done
echo "delivery status: $state"

step "Inspect result: delivery and attempt history"
jq '{status: .delivery.status, deadLetterReason: .delivery.deadLetterReason, attempts: [.attempts[] |
     {n: .attemptNumber, outcome, responseStatus, durationMs, errorMessage}]}' <<<"$detail"

step "Receiver's view (simulator request log)"
curl -sf "$SIM/receivers/$RECEIVER/requests" | jq '[.[] | {webhookId, deliveryId, attempt, signatureResult, duplicate, respondedWith}]'

step "Repeat the same submission (same Idempotency-Key)"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "${auth[@]}" "${json[@]}" -H "Idempotency-Key: order-$RUN" \
  -X POST "$API/api/v1/events" \
  -d "{\"eventType\":\"order.created\",\"payload\":{\"orderId\":\"ord_$RUN\",\"amount\":4999},\"endpointIds\":[\"$endpoint_id\"]}"
step "Same key, different payload"
curl -s "${auth[@]}" "${json[@]}" -H "Idempotency-Key: order-$RUN" -X POST "$API/api/v1/events" \
  -d "{\"eventType\":\"order.created\",\"payload\":{\"orderId\":\"other\"},\"endpointIds\":[\"$endpoint_id\"]}" | jq -c '{status, code}'
