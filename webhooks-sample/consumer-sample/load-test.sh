#!/usr/bin/env bash
#
# Dependency-free load baseline for the running sample (port 9091). Fires N signed POSTs at the CRM
# endpoint with C-way concurrency and reports throughput + a latency distribution (p50/p90/p99),
# computed from curl's per-request timing. Uses only bash + curl + openssl + awk.
#
#   Usage:  ./load-test.sh [requests] [concurrency]     (default 2000 50)
#   App must be running:  (from sample/) mvn -pl consumer-sample spring-boot:run
#                         or via Docker:  docker compose up consumer
#
# For richer percentiles / sustained-rate scenarios prefer the k6 script (load-test.js).
set -u

BASE="http://localhost:9091"
PATH_="/webhooks/crm"
SECRET_B64="MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"   # base64 body of whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw
REQUESTS="${1:-2000}"
CONC="${2:-50}"
HEXKEY=$(printf '%s' "$SECRET_B64" | base64 -d | xxd -p -c 256)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

sign() { # <id> <ts> <body>
  printf '%s.%s.%s' "$1" "$2" "$3" \
    | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$HEXKEY" -binary | base64
}

# One signed request; prints "<http_code> <time_total_seconds>".
one() {
  local n="$1"
  local id="msg_${n}" ts; ts=$(( $(date +%s) ))
  local body="{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344Z\",\"data\":{\"id\":\"c-${n}\",\"fullName\":\"Ada\",\"email\":\"ada@example.com\"}}"
  local sig; sig="v1,$(sign "$id" "$ts" "$body")"
  curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -X POST "$BASE$PATH_" \
    -H "content-type: application/json" -H "webhook-id: $id" \
    -H "webhook-timestamp: $ts" -H "webhook-signature: $sig" --data "$body"
}
export -f one sign
export BASE PATH_ HEXKEY

echo "== Load baseline: $REQUESTS requests, concurrency $CONC -> $BASE$PATH_ =="
START=$(date +%s.%N)
seq 1 "$REQUESTS" | xargs -P "$CONC" -I{} bash -c 'one "$@"' _ {} > "$TMP/out.txt"
END=$(date +%s.%N)

# Status-code tally (unsorted).
awk '{ code[$1]++ } END { printf "  status codes :"; for (c in code) printf " %s=%d", c, code[c]; printf "\n" }' "$TMP/out.txt"

# Latency percentiles: extract ms, sort numerically (portable), then index into the sorted array.
awk '{ print $2*1000 }' "$TMP/out.txt" | sort -n > "$TMP/lat.txt"
awk -v start="$START" -v end="$END" -v reqs="$REQUESTS" '
  { t[NR]=$1; sum+=$1 }
  END {
    n=NR; wall=end-start
    i50=int(n*0.50); if(i50<1)i50=1
    i90=int(n*0.90); if(i90<1)i90=1
    i99=int(n*0.99); if(i99<1)i99=1
    printf "  requests     : %d\n", reqs
    printf "  wall time    : %.2fs\n", wall
    printf "  throughput   : %.0f req/s\n", reqs/wall
    printf "  latency mean : %.1f ms\n", sum/n
    printf "  latency p50  : %.1f ms\n", t[i50]
    printf "  latency p90  : %.1f ms\n", t[i90]
    printf "  latency p99  : %.1f ms\n", t[i99]
  }' "$TMP/lat.txt"
