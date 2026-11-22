// k6 load test for the Standard Webhooks sample (port 9091).
//
// Signs each request with a valid Standard Webhooks signature in-script, so it exercises the REAL
// hot path: verify -> parse -> deserialize -> validate -> dispatch. Reports p50/p95/p99
// latency and throughput, split by endpoint via tags.
//
//   Install:  brew install k6
//   Run:      k6 run load-test.js
//             k6 run -e VUS=100 -e DURATION=60s load-test.js
//
// The app must already be running:  (from webhooks-sample/) mvn -pl consumer-sample spring-boot:run
//                                   or via Docker:  docker compose up consumer
import http from "k6/http";
import crypto from "k6/crypto";
import encoding from "k6/encoding";
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
    "http_req_duration{endpoint:crm_created}": ["p(99)<250"],
  },
};

const createdLatency = new Trend("crm_created_latency", true);

// Decode the base64 secret body into a raw ArrayBuffer ("a") once: passing a JS string to crypto.hmac
// UTF-8-encodes char codes >= 0x80 and corrupts binary keys.
const SECRET_KEY = encoding.b64decode(SECRET_B64, "std", "a");

// Standard Webhooks signature: v1,<base64 HMAC-SHA256 of "id.timestamp.body">.
function sign(id, ts, body) {
  return `v1,${crypto.hmac("sha256", SECRET_KEY, `${id}.${ts}.${body}`, "base64")}`;
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
  return http.post(`${BASE}${path}`, body, { headers, tags: { endpoint: "crm_created" } });
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
