#!/usr/bin/env bash
#
# End-to-end smoke test for the running consumer sample (port 9091). Signs real Standard Webhooks
# requests with openssl and exercises the full request matrix: multi-type CRM, single-type billing,
# bad signature, oversized body, unknown type, and the unsigned case.
#
# Signed content is "<id>.<timestamp>.<body>"; the header is "webhook-signature: v1,<base64 hmac>".
# The demo secret's base64 body (after the whsec_ prefix) is the HMAC key.
#
# Usage:  ./smoke-test.sh        (consumer must already be running on :9091)
#   Start it:  (from sample/) mvn -pl consumer-sample spring-boot:run
#              or via Docker:  docker compose up consumer
set -u

BASE="http://localhost:9091"
SECRET_B64="MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"   # base64 body of whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw
# v2a (asymmetric) demo keys — the producer PRIVATE key (whsk_) and the shared endpoint-id. The
# consumer verifies with the matching whpk_ PUBLIC key; see application.yaml's /webhooks/crm-v2a endpoint.
V2A_PRIVATE_KEY="whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY="
V2A_ENDPOINT_ID="ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb"
PASS=0; FAIL=0

# sign <id> <timestamp> <body> -> prints "v1,<base64 hmac-sha256>"
sign() {
  local id="$1" ts="$2" body="$3"
  local sig
  sig=$(printf '%s.%s.%s' "$id" "$ts" "$body" \
        | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$(printf '%s' "$SECRET_B64" | base64 -d | xxd -p -c 256)" -binary \
        | base64)
  printf 'v1,%s' "$sig"
}

# sign_v2a <id> <timestamp> <body> -> prints "v2a,<base64 ed25519 over SHA-256(endpoint_id.id.ts.body)>"
# Delegates to python3 + cryptography (same math as sign.py); prints nothing if unavailable.
sign_v2a() {
  local id="$1" ts="$2" body="$3"
  python3 - "$V2A_PRIVATE_KEY" "$V2A_ENDPOINT_ID" "$id" "$ts" "$body" <<'PY' 2>/dev/null
import base64, hashlib, sys
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
except ImportError:
    sys.exit(1)
sk, ep, mid, ts, body = sys.argv[1:6]
key = Ed25519PrivateKey.from_private_bytes(base64.b64decode(sk[5:]))
digest = hashlib.sha256(f"{ep}.{mid}.{ts}.{body}".encode()).digest()
print("v2a," + base64.b64encode(key.sign(digest)).decode())
PY
}

# post <name> <path> <body> <sign?yes/no> <expected-status> [extra curl args...]
post() {
  local name="$1" path="$2" body="$3" dosign="$4" expect="$5"; shift 5
  local id="msg_$(date +%s%N | cut -c1-13)"
  local ts; ts=$(( $(date +%s) ))
  local -a hdrs=(-H "content-type: application/json" -H "webhook-id: $id" -H "webhook-timestamp: $ts")
  if [ "$dosign" = "yes" ]; then
    hdrs+=(-H "webhook-signature: $(sign "$id" "$ts" "$body")")
  elif [ "$dosign" = "v2a" ]; then
    local v2asig; v2asig=$(sign_v2a "$id" "$ts" "$body")
    if [ -z "$v2asig" ]; then
      printf '  \033[33mSKIP\033[0m  %-45s (python3 + cryptography not available for v2a)\n' "$name"; return
    fi
    hdrs+=(-H "webhook-signature: $v2asig")
  elif [ "$dosign" = "bad" ]; then
    hdrs+=(-H "webhook-signature: v1,AAAAinvalidsignatureAAAA==")
  fi
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE$path" "${hdrs[@]}" "$@" --data "$body")
  if [ "$code" = "$expect" ]; then
    printf '  \033[32mPASS\033[0m  %-45s -> %s\n' "$name" "$code"; PASS=$((PASS+1))
  else
    printf '  \033[31mFAIL\033[0m  %-45s -> got %s, expected %s\n' "$name" "$code" "$expect"; FAIL=$((FAIL+1))
  fi
}

echo "== Standard Webhooks sample smoke test ($BASE) =="

# --- Happy paths (valid signature, validation-valid, allow-listed) ---
post "CRM contact.created (multi-type, redacted email)" /webhooks/crm \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1","fullName":"Ada Lovelace","email":"ada@example.com"}}' \
  yes 204

post "CRM contact.updated (event+metadata)" /webhooks/crm \
  '{"type":"contact.updated","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-2","fullName":"Grace","email":"grace@example.com"}}' \
  yes 204

post "CRM contact.deleted (metadata+event)" /webhooks/crm \
  '{"type":"contact.deleted","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-3"}}' \
  yes 204

post "Billing invoice.paid (single-type)" /webhooks/billing/invoice-paid \
  '{"type":"invoice.paid","timestamp":"2022-11-03T20:26:10.344Z","data":{"invoiceId":"inv_1","customerId":"cus_9","amount":42.50,"currency":"GBP"}}' \
  yes 204

# --- Asymmetric (v2a) happy path: signed with the whsk_ private key, verified with the whpk_ public key ---
post "CRM contact.created (v2a, Ed25519 asymmetric)" /webhooks/crm-v2a \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-v2a","fullName":"Ada Lovelace","email":"ada@example.com"}}' \
  v2a 204

# --- Failure paths ---

# A v1 (HMAC) signature can never verify at the v2a endpoint, which expects an Ed25519 signature.
post "v1 signature at v2a endpoint -> 400" /webhooks/crm-v2a \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1","email":"ada@example.com"}}' \
  yes 400

post "Bad signature -> 400" /webhooks/crm \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1","email":"ada@example.com"}}' \
  bad 400

post "Unsigned (missing signature) -> 400" /webhooks/crm \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1","email":"ada@example.com"}}' \
  no 400

post "Validation-invalid (missing required email) -> 400" /webhooks/crm \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1"}}' \
  yes 400

post "Type not allow-listed on billing -> 204 (ack-and-ignore)" /webhooks/billing/invoice-paid \
  '{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-1","email":"ada@example.com"}}' \
  yes 204

post "Unknown type (no @Type class) -> 204 (ack-and-ignore)" /webhooks/crm \
  '{"type":"contact.archived","timestamp":"2022-11-03T20:26:10.344Z","data":{"id":"c-9"}}' \
  yes 204

# Oversized body: > 256KB default max-body-size -> 413. Build a ~300KB padded payload.
BIG=$(printf '%0.sX' $(seq 1 307200))
post "Oversized body (>256KB) -> 413" /webhooks/crm \
  "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344Z\",\"data\":{\"id\":\"c-1\",\"email\":\"a@b.co\",\"pad\":\"$BIG\"}}" \
  yes 413

echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" = "0" ]
