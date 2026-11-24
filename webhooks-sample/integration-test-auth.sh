#!/usr/bin/env bash
#
# End-to-end Docker integration test for the OAuth2 JWT + Standard Webhooks dual-layer auth stack:
#   1) Direct unauthenticated POST to consumer-auth -> rejected with 401 Unauthorized.
#   2) Publish via producer-auth -> mints Bearer JWT + v1 HMAC signature -> consumer-auth verifies both -> 202.
#
#   Usage:  ./integration-test-auth.sh
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.auth.yml"
PRODUCER="http://localhost:9094"
CONSUMER="http://localhost:9095"
PASS=0; FAIL=0
TMP_OUT=$(mktemp)

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ] || [ "$FAIL" -ne 0 ]; then
    echo "== Failure detected; dumping auth stack logs =="
    $COMPOSE logs || true
  fi
  echo "== Tearing down auth stack =="
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

echo "== Building and starting producer-auth + consumer-auth =="
$COMPOSE up --build -d

echo "== Waiting for producer-auth health =="
for i in $(seq 1 40); do
  if curl -fsS "$PRODUCER/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
  if [ "$i" -eq 40 ]; then echo "producer-auth did not come up"; exit 1; fi
done

echo "== Verifying unauthenticated webhook POST is rejected with 401 =="
UNAUTH_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$CONSUMER/webhooks/crm" \
  -H 'content-type: application/json' --data '{}')
if [ "$UNAUTH_CODE" = "401" ]; then
  check "consumer-auth rejected unauthenticated POST with 401" 0
else
  check "consumer-auth rejected unauthenticated POST with 401" 1
fi

echo "== Publishing contact.created via producer-auth =="
BODY='{"id":"c-auth-1","fullName":"Ada Lovelace","email":"ada@example.com"}'
HTTP_CODE=$(curl -s -o "$TMP_OUT" -w '%{http_code}' \
  -X POST "$PRODUCER/publish/contact-created" \
  -H 'content-type: application/json' --data "$BODY")
echo "  producer-auth responded $HTTP_CODE: $(cat "$TMP_OUT")"
if [ "$HTTP_CODE" = "202" ]; then
  check "producer-auth sent JWT-authenticated + HMAC-signed webhook (202)" 0
else
  check "producer-auth sent JWT-authenticated + HMAC-signed webhook (202)" 1
fi

sleep 1

if $COMPOSE logs consumer-auth 2>&1 | grep -q "Authenticated + verified contact.created id=c-auth-1"; then
  check "consumer-auth verified Bearer JWT + webhook-signature and dispatched contact.created" 0
else
  check "consumer-auth verified Bearer JWT + webhook-signature and dispatched contact.created" 1
fi

echo
echo "== Result: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
