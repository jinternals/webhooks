#!/usr/bin/env bash
#
# End-to-end Docker integration test for the NATS JetStream zero-webhook-loss stack:
#   producer-nats-sample -> NATS WEBHOOKS_OUTBOUND -> relay (fresh signature) ->
#   consumer-nats-sample -> NATS WEBHOOKS_INBOUND (+ NATS KV idempotency) -> domain worker.
#
#   Usage:  ./integration-test-nats.sh
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.nats.yml"
PRODUCER="http://localhost:9092"
CONSUMER="http://localhost:9093"
PASS=0; FAIL=0
TMP_OUT=$(mktemp)

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ] || [ "$FAIL" -ne 0 ]; then
    echo "== Failure detected; dumping NATS stack logs =="
    $COMPOSE logs || true
  fi
  echo "== Tearing down NATS stack =="
  rm -f "$TMP_OUT"
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() {
  if [ "$2" -eq 0 ]; then
    echo "  PASS: $1"; PASS=$((PASS + 1))
  else
    echo "  FAIL: $1"; FAIL=$((FAIL + 1))
  fi
}

echo "== Building and starting NATS + producer-nats + consumer-nats =="
$COMPOSE up --build -d

echo "== Waiting for producer-nats health =="
for i in $(seq 1 40); do
  if curl -fsS "$PRODUCER/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
  if [ "$i" -eq 40 ]; then echo "producer-nats did not come up"; exit 1; fi
done

echo "== Publishing contact.created via producer-nats =="
BODY='{"id":"c-nats-1","fullName":"Ada Lovelace","email":"ada@example.com"}'
HTTP_CODE=$(curl -s -o "$TMP_OUT" -w '%{http_code}' \
  -X POST "$PRODUCER/publish/contact-created" \
  -H 'content-type: application/json' --data "$BODY")
echo "  producer-nats responded $HTTP_CODE: $(cat "$TMP_OUT")"
if [ "$HTTP_CODE" = "202" ]; then
  check "producer-nats persisted webhook to NATS JetStream (202)" 0
else
  check "producer-nats persisted webhook to NATS JetStream (202)" 1
fi

echo "== Waiting for relay -> consumer-nats -> inbound worker =="
for i in $(seq 1 20); do
  if $COMPOSE logs consumer-nats 2>&1 | grep -q "contact.created processed from NATS inbox id=c-nats-1"; then
    break
  fi
  sleep 1
done

if $COMPOSE logs consumer-nats 2>&1 | grep -q "contact.created processed from NATS inbox id=c-nats-1"; then
  check "consumer-nats verified, persisted to NATS inbox, and processed contact.created" 0
else
  check "consumer-nats verified, persisted to NATS inbox, and processed contact.created" 1
fi

METRICS=$(curl -fsS "$CONSUMER/actuator/prometheus" || true)
if echo "$METRICS" | grep -E 'webhook_received_total\{[^}]*type="contact.created"' | grep -qvE ' 0(\.0)?$'; then
  check "consumer-nats webhook_received_total incremented" 0
else
  check "consumer-nats webhook_received_total incremented" 1
fi

echo
echo "== Result: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
