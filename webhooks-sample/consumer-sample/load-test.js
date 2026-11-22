// k6 load test for the Standard Webhooks sample (port 9091).
//
// Signs each request with a valid Standard Webhooks signature in-script, so it exercises the REAL
// hot path: verify -> parse -> deserialize -> validate -> dispatch. Reports p50/p95/p99
// latency and throughput, split by scenario via tags.
//
//   Install:  brew install k6
//   Run:      k6 run load-test.js
//             k6 run -e VUS=100 -e DURATION=60s load-test.js
//
// The app must already be running:  (from webhooks-sample/) mvn -pl consumer-sample spring-boot:run
//                                   or via Docker:  docker compose up consumer
import http from "k6/http";
import crypto from "k6/crypto";
import { check } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:9091";
const SECRET_B64 = "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"; // base64 body of whsec_...
const VUS = parseInt(__ENV.VUS || "50", 10);
const DURATION = __ENV.DURATION || "30s";

export const options = {
  scenarios: {
    load: { executor: "constant-vus", vus: VUS, duration: DURATION },
  },
  thresholds: {
    // Baseline guard-rails; tune to your target SLO once you have numbers.
    http_req_failed: ["rate<0.01"],
    "http_req_duration{scenario:crm_created}": ["p(99)<250"],
  },
};

const createdLatency = new Trend("crm_created_latency", true);

// The HMAC key is the RAW bytes of the base64 secret body. k6's crypto.hmac takes the key as a
// string whose char codes are the key bytes, so we base64-decode the secret into such a string once.
const SECRET_KEY = b64decode(SECRET_B64);

// Standard Webhooks signature: v1,<base64 HMAC-SHA256 of "id.timestamp.body">.
function sign(id, ts, body) {
  return `v1,${crypto.hmac("sha256", SECRET_KEY, `${id}.${ts}.${body}`, "base64")}`;
}

// Minimal base64 -> binary (latin1) string decode (k6 has no atob).
function b64decode(b64) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let out = "", bits = 0, val = 0;
  for (const c of b64.replace(/=+$/, "")) {
    val = (val << 6) | chars.indexOf(c);
    bits += 6;
    if (bits >= 8) { bits -= 8; out += String.fromCharCode((val >> bits) & 0xff); }
  }
  return out;
}

function post(path, body) {
  const id = `msg_${__VU}_${__ITER}`;
  const ts = Math.floor(Date.now() / 1000).toString();
  const headers = {
    "Content-Type": "application/json",
    "webhook-id": id,
    "webhook-timestamp": ts,
    "webhook-signature": sign(id, ts, body),
  };
  return http.post(`${BASE}${path}`, body, { headers, tags: { scenario: "crm_created" } });
}

export default function () {
  const body = JSON.stringify({
    type: "contact.created",
    timestamp: "2022-11-03T20:26:10.344Z",
    data: { id: `c-${__VU}-${__ITER}`, fullName: "Ada Lovelace", email: "ada@example.com" },
  });
  const res = post("/webhooks/crm", body);
  createdLatency.add(res.timings.duration);
  check(res, { "status is 204": (r) => r.status === 204 });
}
