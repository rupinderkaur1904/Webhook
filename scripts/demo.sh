#!/usr/bin/env bash
# Demonstrations against a running local stack (backend :8080 with SPRING_PROFILES_ACTIVE=local,
# simulator :9090). The local profile retries quickly (4 attempts, 2s base delay) and times out
# receiver responses after 5s. Every scenario is driven by the simulator's scripted behaviour: no
# database edits and no reconfiguration while a delivery is in progress, except where a person would
# act (fixing the receiver before a replay).
#
# Usage: scripts/demo.sh <scenario>
#   success       receiver answers 200: delivered on the first attempt, signature verified
#   recover       receiver script [500, 500] then 200: retried until delivered on attempt 3
#   rate-limit    receiver script [429 Retry-After: 5] then 200: the retry waits >= 5s
#   timeout       receiver script [200 after 8s] then 200: attempt 1 times out, the retry succeeds,
#                 and the receiver sees the same webhook-id twice
#   exhaust       receiver always 503: retries until the budget is used, then dead-lettered
#   permanent     receiver answers 400: dead-lettered immediately, no retries
#   intermittent  receiver fails 50% of requests (seeded): 8 events, each retried until delivered
#   replay        exhaust, fix the receiver, replay the dead letter; a second replay is refused
#   idempotency   same Idempotency-Key twice (200 replay), then with a different payload (409)
#   signature     check a delivered request's signature, then the same request with a tampered body
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

receiver() {  # receiver <behaviour-json>: status, delayMs, headers, script, failureRate, failureStatus, seed
  local config
  config=$(jq -c --arg secret "$SECRET" '. + {secret: $secret}' <<<"$1")
  curl -sf "${json[@]}" -X PUT "$SIM/receivers/$RECEIVER/config" -d "$config" >/dev/null
  echo "simulator behaviour: $1"
}

delivery() { curl -sf "${auth[@]}" "$API/api/v1/deliveries/${1:-$DELIVERY}"; }

wait_until() {  # wait_until <jq-condition> <timeout-seconds> [delivery-id]
  local deadline=$((SECONDS + $2))
  until delivery "${3:-$DELIVERY}" | jq -e "$1" >/dev/null; do
    (( SECONDS < deadline )) || { echo "timed out waiting for: $1" >&2; exit 1; }
    sleep 0.5
  done
}

show() {  # show [delivery-id]
  step "Delivery ${1:-$DELIVERY}: state and attempt history"
  delivery "${1:-$DELIVERY}" | jq '{status: .delivery.status, attempts: "\(.delivery.attemptCount)/\(.delivery.maxAttempts)",
    deadLetterReason: .delivery.deadLetterReason, replayOf: .delivery.replayOfDeliveryId,
    history: [.attempts[] | {n: .attemptNumber, startedAt, outcome, http: .responseStatus, retryable, retryAfterSeconds, ms: .durationMs}]}'
}

show_receiver() {
  step "Receiver's view (simulator request log)"
  curl -sf "$SIM/receivers/$RECEIVER/requests" |
    jq '[.[] | {at: .receivedAt, webhookId, deliveryId: .deliveryId[0:8], attempt, signatureResult, duplicateWebhookId: .duplicate, respondedWith, rule}]'
}

submit() {  # submit <idempotency-key> <payload-json>; prints "<body>\n<http status>"
  curl -s -w '\n%{http_code}' "${auth[@]}" "${json[@]}" -H "Idempotency-Key: $1" -X POST "$API/api/v1/events" \
    -d "{\"eventType\":\"order.created\",\"payload\":$2,\"endpointIds\":[\"$ENDPOINT\"]}"
}

submit_one() {  # submits an event and sets DELIVERY
  local response
  response=$(submit "order-$RUN${1:-}" "{\"orderId\":\"ord_$RUN${1:-}\",\"amount\":4999}")
  echo "HTTP $(tail -n1 <<<"$response") (accepted before any delivery attempt)"
  DELIVERY=$(sed '$d' <<<"$response" | jq -r '.deliveries[0].id')
  echo "delivery $DELIVERY"
}

step "Register endpoint for simulator receiver '$RECEIVER'"
created=$(curl -sf "${auth[@]}" "${json[@]}" -X POST "$API/api/v1/endpoints" \
  -d "{\"name\":\"$RECEIVER\",\"url\":\"$SIM_FROM_API/receivers/$RECEIVER\"}")
ENDPOINT=$(jq -r .endpoint.id <<<"$created")
SECRET=$(jq -r .secret <<<"$created")
echo "endpoint $ENDPOINT (signing secret ${SECRET:0:12}..., shown once; simulator verifies with it)"

case "$SCENARIO" in
  success|idempotency|signature) receiver '{"status":200}' ;;
  recover)      receiver '{"status":200,"script":[{"status":500},{"status":500}]}' ;;
  rate-limit)   receiver '{"status":200,"script":[{"status":429,"headers":{"Retry-After":"5"}}]}' ;;
  timeout)      receiver '{"status":200,"script":[{"status":200,"delayMs":8000}]}' ;;
  exhaust|replay) receiver '{"status":503}' ;;
  permanent)    receiver '{"status":400}' ;;
  intermittent) receiver '{"status":200,"failureRate":0.5,"failureStatus":503,"seed":7}' ;;
  *) echo "unknown scenario: $SCENARIO" >&2; exit 2 ;;
esac

if [[ "$SCENARIO" == "intermittent" ]]; then
  step "Submit 8 events"
  ids=()
  for i in 1 2 3 4 5 6 7 8; do
    response=$(submit "order-$RUN-$i" "{\"orderId\":\"ord_$RUN-$i\"}")
    ids+=("$(sed '$d' <<<"$response" | jq -r '.deliveries[0].id')")
  done
  echo "${#ids[@]} events accepted"
  step "Wait until every delivery is delivered or dead-lettered"
  for id in "${ids[@]}"; do wait_until '.delivery.status == "DELIVERED" or .delivery.status == "DEAD_LETTERED"' 90 "$id"; done
  step "Outcome per delivery"
  for id in "${ids[@]}"; do
    delivery "$id" | jq -c '{delivery: .delivery.id[0:8], status: .delivery.status, attempts: [.attempts[].responseStatus]}'
  done
  step "Receiver totals"
  curl -sf "$SIM/receivers/$RECEIVER/requests" |
    jq -c '{requests: length, answered: (group_by(.respondedWith) | map({key: (.[0].respondedWith|tostring), value: length}) | from_entries),
            retriesSeenAsDuplicateWebhookIds: (map(select(.duplicate)) | length)}'
  exit 0
fi

step "Submit event"
submit_one

case "$SCENARIO" in
  success|idempotency|signature)
    wait_until '.delivery.status == "DELIVERED"' 15 ;;
  recover|rate-limit)
    wait_until '.delivery.status == "DELIVERED"' 40 ;;
  timeout)
    step "Attempt 1 waits for the 5s response timeout, then a retry follows"
    wait_until '.delivery.status == "DELIVERED"' 40 ;;
  exhaust|replay)
    step "Wait for the retry budget to run out (about 15s)"
    wait_until '.delivery.status == "DEAD_LETTERED"' 60 ;;
  permanent)
    wait_until '.delivery.status == "DEAD_LETTERED"' 15 ;;
esac
show

if [[ "$SCENARIO" == "exhaust" ]]; then
  step "Dead-letter view (GET /api/v1/deliveries?status=DEAD_LETTERED&endpointId=...)"
  curl -sf "${auth[@]}" "$API/api/v1/deliveries?status=DEAD_LETTERED&endpointId=$ENDPOINT" |
    jq -c '.items[] | {id: .id[0:8], deadLetterReason, attempts: .attemptCount}'
  delivery | jq -c '{replayEligibility}'
fi

if [[ "$SCENARIO" == "replay" ]]; then
  ORIGINAL=$DELIVERY
  step "Replay eligibility of the dead letter"
  delivery | jq -c '.replayEligibility'
  step "Fix the receiver, then replay"
  receiver '{"status":200}'
  replayed=$(curl -s -w '\n%{http_code}' "${auth[@]}" -X POST "$API/api/v1/deliveries/$ORIGINAL/replay")
  echo "HTTP $(tail -n1 <<<"$replayed") $(sed '$d' <<<"$replayed" | jq -c '{id, status, replayOfDeliveryId, maxAttempts}')"
  DELIVERY=$(sed '$d' <<<"$replayed" | jq -r .id)
  step "Second replay while the first is in progress"
  second=$(curl -s -w '\n%{http_code}' "${auth[@]}" -X POST "$API/api/v1/deliveries/$ORIGINAL/replay")
  echo "HTTP $(tail -n1 <<<"$second") $(sed '$d' <<<"$second" | jq -c '{code, reason, detail}')"
  wait_until '.delivery.status == "DELIVERED"' 15
  show "$DELIVERY"
  step "The original dead letter is unchanged"
  show "$ORIGINAL"
  step "Replay chain"
  delivery "$ORIGINAL" | jq -c '.chain[] | {id: .id[0:8], status, replayOf: (.replayOfDeliveryId // "" | .[0:8]), attempts: .attemptCount}'
fi
show_receiver

if [[ "$SCENARIO" == "idempotency" ]]; then
  step "Same Idempotency-Key, same request"
  r=$(submit "order-$RUN" "{\"orderId\":\"ord_$RUN\",\"amount\":4999}")
  echo "HTTP $(tail -n1 <<<"$r"), same event id: $(sed '$d' <<<"$r" | jq -r .id)"
  step "Same Idempotency-Key, different payload"
  r=$(submit "order-$RUN" '{"orderId":"something-else"}')
  echo "HTTP $(tail -n1 <<<"$r") $(sed '$d' <<<"$r" | jq -c '{code, detail}')"
fi

if [[ "$SCENARIO" == "signature" ]]; then
  request=$(curl -sf "$SIM/receivers/$RECEIVER/requests" | jq '.[-1]')
  verify() {  # verify <secret> <body>
    jq -n --arg secret "$1" --arg body "$2" --argjson r "$request" \
      '{secret: $secret, webhookId: $r.headers["webhook-id"], webhookTimestamp: $r.headers["webhook-timestamp"],
        webhookSignature: $r.headers["webhook-signature"], body: $body}' |
      curl -sf "${json[@]}" -X POST "$SIM/verify" -d @- | jq -r .result
  }
  step "Headers the receiver got"
  jq '.headers | with_entries(select(.key | startswith("webhook-") or startswith("x-webhook")))' <<<"$request"
  body=$(jq -r .body <<<"$request")
  step "Verify the request exactly as received"
  verify "$SECRET" "$body"
  step "Verify the same signature with a tampered body (amount 4999 -> 1)"
  verify "$SECRET" "${body/4999/1}"
  step "Verify with a different secret"
  verify "whsec_$(head -c 32 /dev/urandom | base64)" "$body"
fi
