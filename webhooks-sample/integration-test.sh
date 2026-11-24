#!/usr/bin/env bash
#
# Cross-library integration test: producer sample -> consumer sample, end to end over HTTP via Docker.
#
# Brings up both standalone sample apps with docker compose, then POSTs to the PRODUCER's
# /publish/contact-created. The producer signs a Standard Webhooks envelope and delivers it to the
# CONSUMER's /webhooks/crm; the consumer verifies the signature, deserializes + validates the event,
# and dispatches it to the @WebhookHandler. We assert:
#
#   1) the producer returns 202 Accepted (it sent the webhook), and
#   2) the consumer actually handled it — its logs show the contact.created handler line AND its
#      webhook.received metric incremented for type=contact.created.
#
# This replaces the old in-JVM CrossLibraryWebhookIntegrationTest with a real two-process check.
#
#   Usage:  ./integration-test.sh
#   Requires: docker (with the compose plugin), curl.
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE="docker compose"
PRODUCER="http://localhost:9090"
CONSUMER="http://localhost:9091"
PASS=0; FAIL=0
TMP_OUT=$(mktemp)

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ] || [ "$FAIL" -ne 0 ]; then
    echo "== Failure detected; dumping container logs =="
    $COMPOSE logs || true
  fi
  echo "== Tearing down =="
  rm -f "$TMP_OUT"
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

check() { # <description> <condition-already-evaluated:0/1>
  if [ "$2" -eq 0 ]; then
    echo "  PASS: $1"; PASS=$((PASS + 1))
  else
    echo "  FAIL: $1"; FAIL=$((FAIL + 1))
  fi
}

echo "== Building and starting producer + consumer =="
$COMPOSE up --build -d

# Wait for the producer to be reachable (the consumer's readiness is gated by compose healthcheck).
echo "== Waiting for the producer to accept requests =="
for i in $(seq 1 40); do
  if curl -fsS "$PRODUCER/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
  if [ "$i" -eq 40 ]; then echo "producer did not come up"; exit 1; fi
done

echo "== Publishing a contact.created (v1 HMAC) via the producer =="
BODY='{"id":"c-int-1","fullName":"Ada Lovelace","email":"ada@example.com"}'
HTTP_CODE=$(curl -s -o "$TMP_OUT" -w '%{http_code}' \
  -X POST "$PRODUCER/publish/contact-created" \
  -H 'content-type: application/json' --data "$BODY")
echo "  producer (v1) responded $HTTP_CODE: $(cat "$TMP_OUT")"
if [ "$HTTP_CODE" = "202" ]; then
  check "producer accepted and sent the v1 webhook (202)" 0
else
  check "producer accepted and sent the v1 webhook (202)" 1
fi

echo "== Publishing a contact.created (v2a Ed25519) via the producer =="
BODY_V2A='{"id":"c-int-v2a","fullName":"Grace Hopper","email":"grace@example.com"}'
HTTP_CODE_V2A=$(curl -s -o "$TMP_OUT" -w '%{http_code}' \
  -X POST "$PRODUCER/publish/contact-created-v2a" \
  -H 'content-type: application/json' --data "$BODY_V2A")
echo "  producer (v2a) responded $HTTP_CODE_V2A: $(cat "$TMP_OUT")"
if [ "$HTTP_CODE_V2A" = "202" ]; then
  check "producer accepted and sent the v2a webhook (202)" 0
else
  check "producer accepted and sent the v2a webhook (202)" 1
fi

# Give the consumer a moment to process and update its metrics.
sleep 2

echo "== Asserting the consumer handled both =="
# 1) The handler log line proves dispatch reached the @WebhookHandler for both v1 and v2a.
if $COMPOSE logs consumer 2>&1 | grep -q "contact.created id=c-int-1"; then
  check "consumer dispatched v1 contact.created to its handler (log line)" 0
else
  check "consumer dispatched v1 contact.created to its handler (log line)" 1
fi
if $COMPOSE logs consumer 2>&1 | grep -q "contact.created id=c-int-v2a"; then
  check "consumer dispatched v2a contact.created to its handler (log line)" 0
else
  check "consumer dispatched v2a contact.created to its handler (log line)" 1
fi

# 2) The received counter confirms the full verify->validate->dispatch path ran for this type.
#    Micrometer's Prometheus registry renders webhook.received{type=...} as
#    webhook_received_total{type="contact.created"}.
METRICS=$(curl -fsS "$CONSUMER/actuator/prometheus" || true)
if echo "$METRICS" | grep -E 'webhook_received_total\{[^}]*type="contact.created"' | grep -qvE ' 0(\.0)?$'; then
  check "consumer's webhook_received_total incremented for contact.created" 0
else
  check "consumer's webhook_received_total incremented for contact.created" 1
fi
if echo "$METRICS" | grep -E 'webhook_receive_seconds_count\{' | grep -qvE ' 0(\.0)?$'; then
  check "consumer's @Observed webhook_receive_seconds_count timer incremented" 0
else
  check "consumer's @Observed webhook_receive_seconds_count timer incremented" 1
fi

PRODUCER_METRICS=$(curl -fsS "$PRODUCER/actuator/prometheus" || true)
if echo "$PRODUCER_METRICS" | grep -E 'webhook_send_seconds_count\{' | grep -qvE ' 0(\.0)?$'; then
  check "producer's @Observed webhook_send_seconds_count timer incremented" 0
else
  check "producer's @Observed webhook_send_seconds_count timer incremented" 1
fi

echo
echo "== Result: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
