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

cleanup() {
  echo "== Tearing down =="
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

echo "== Publishing a contact.created via the producer =="
BODY='{"id":"c-int-1","fullName":"Ada Lovelace","email":"ada@example.com"}'
HTTP_CODE=$(curl -s -o /tmp/publish-out.txt -w '%{http_code}' \
  -X POST "$PRODUCER/publish/contact-created" \
  -H 'content-type: application/json' --data "$BODY")
echo "  producer responded $HTTP_CODE: $(cat /tmp/publish-out.txt)"
[ "$HTTP_CODE" = "202" ]; check "producer accepted and sent the webhook (202)" $?

# Give the consumer a moment to process and update its metrics.
sleep 2

echo "== Asserting the consumer handled it =="
# 1) The handler log line proves dispatch reached the @WebhookHandler.
if $COMPOSE logs consumer 2>&1 | grep -q "contact.created id=c-int-1"; then
  check "consumer dispatched contact.created to its handler (log line)" 0
else
  check "consumer dispatched contact.created to its handler (log line)" 1
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

echo
echo "== Result: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
