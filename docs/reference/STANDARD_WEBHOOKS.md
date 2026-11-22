# Standard Webhooks

> Open source tools and guidelines for sending webhooks easily, securely and reliably.
>
> Source: <https://www.standardwebhooks.com/> · [The Spec](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)
> Version: 1.0.0 · License: Apache 2.0

Standard Webhooks is a community-driven specification (guided by a technical steering committee
with members from Zapier, Twilio, Lob, Mux, ngrok, Supabase, Svix, and Kong) that standardizes
how webhooks are sent and verified across providers. The goal is to do for webhooks what JWT did
for API authentication: one consistent, interoperable, secure protocol.

---

## Table of contents

1. [Core concepts](#1-core-concepts)
2. [The payload](#2-the-payload)
3. [HTTP headers](#3-http-headers)
   - [3a. `webhook-timestamp` vs the body `timestamp`](#3a-webhook-timestamp-vs-the-body-timestamp)
4. [Signature scheme](#4-signature-scheme)
5. [Payload samples](#5-payload-samples)
6. [Java: signing & verification samples (success)](#6-java-signing--verification-samples-success)
7. [Java: exception / error samples](#7-java-exception--error-samples)
8. [Delivery success & failure (HTTP status handling)](#8-delivery-success--failure-http-status-handling)
9. [Retry schedule](#9-retry-schedule)
10. [Security checklist](#10-security-checklist)
11. [Tracing & logging (consumer side)](#11-tracing--logging-consumer-side)
12. [Reference libraries](#12-reference-libraries)

---

## 1. Core concepts

Webhooks are HTTP callbacks ("reverse API"): when a service wants to notify a client of an event
(`"a user has paid"`, `"task has finished"`), it triggers a webhook — an HTTP `POST` to a URL the
consumer registered. Both sides run HTTP servers.

Three pieces of metadata travel with every webhook and **all three are signed**:

| Piece          | Header              | Purpose                                                            |
| -------------- | ------------------- | ----------------------------------------------------------------- |
| Unique ID      | `webhook-id`        | Idempotency key — process each event once, even if redelivered.   |
| Timestamp      | `webhook-timestamp` | Unix seconds of the delivery attempt — prevents replay attacks.    |
| Signature(s)   | `webhook-signature` | Space-delimited list of signatures (supports zero-downtime rotation). |

---

## 2. The payload

- Sent in the HTTP body, recommended as JSON.
- Keep payloads small — ideally **< 20 KB**. For large data (images/assets), upload elsewhere and
  send a link.
- Recommended structure:
  - `type` — full-stop delimited event type, hierarchical (e.g. `user.created`, `invoice.paid`).
  - `timestamp` — ISO 8601 time the event occurred (not necessarily when delivered).
  - `data` — the actual event data.

### Example payload

```json
{
  "type": "example.event",
  "timestamp": "2022-11-03T20:26:10.344522Z",
  "data": {
    "foo": "bar",
    "fizzbuzz": 2
  }
}
```

### "Thin" vs "full" payloads

Using a fictional address-book service that fires a webhook when a contact is created:

**Full payload** — all the information, no extra API call needed by the consumer:

```json
{
  "type": "contact.created",
  "timestamp": "2022-11-03T20:26:10.344522Z",
  "data": {
    "id": "1f81eb52-5198-4599-803e-771906343485",
    "type": "contact",
    "fullName": "John Smith",
    "address": "800 W NASA Pkwy, Webster, TX 77598, USA",
    "phoneNumber": "(281) 332-2575",
    "birthday": "1980-04-19",
    "occupation": "Engineer, ACME"
  }
}
```

**Thin payload** — only identifiers; the consumer fetches details via API (better for performance,
flexibility, audit-ability of data access):

```json
{
  "type": "contact.created",
  "timestamp": "2022-11-03T20:26:10.344522Z",
  "data": {
    "id": "1f81eb52-5198-4599-803e-771906343485"
  }
}
```

The choice is not binary — e.g. you can send a thin payload but include a few commonly used fields
like `fullName`.

---

## 3. HTTP headers

All metadata headers are prefixed with `webhook-`:

- `webhook-id`: the unique webhook identifier.
- `webhook-timestamp`: integer unix timestamp (seconds since epoch).
- `webhook-signature`: **space-delimited** list of signatures.

The signature list supports zero-downtime secret rotation: during rotation the webhook is signed
with both the old and new key, and the consumer accepts a match against any entry.

### Example headers

```
webhook-id: msg_2KWPBgLlAfxdpx2AI54pPJ85f4W
webhook-timestamp: 1674087231
webhook-signature: v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4= v1a,hnO3f9T8Ytu9HwrXslvumlUpqtNVqkhqw/enGzPCXe5BdqzCInXqYXFymVJaA7AZdpXwVLPo3mNl8EM+m7TBAg==
```

### 3a. `webhook-timestamp` vs the body `timestamp`

A very common point of confusion: a Standard Webhook carries **two different timestamps** that mean
different things and are used for different purposes. Do not mix them up.

|                        | `webhook-timestamp` (HTTP header)          | `timestamp` (inside the JSON body)              |
| ---------------------- | ------------------------------------------ | ----------------------------------------------- |
| **Location**           | HTTP header                                | Payload (top-level or inside `data`)            |
| **Format**             | Integer unix seconds, e.g. `1674087231`    | ISO 8601 string, e.g. `2022-11-03T20:26:10.344522Z` |
| **Meaning**            | When this **delivery attempt** was made    | When the **event actually occurred**            |
| **Changes on retry?**  | ✅ **Yes** — updated on every retry attempt | ❌ **No** — stays fixed for the life of the event |
| **Role in signature**  | Signed directly as `msg_id.timestamp.payload` | Signed only as part of the body bytes         |
| **Primary purpose**    | **Security** — replay-attack protection    | **Business logic** — when the thing happened    |

#### Why they differ — the retry scenario

The spec is explicit that the attempt time and the event time are not the same:

> *"The timestamp of the attempt may be different to the timestamp of the event that generated the
> attempt. One common example of where this happens: failed deliveries. Every time an attempt is
> retried the timestamp of the attempt is updated, while the timestamp of the original event remains
> the same."*

Say a `contact.created` event occurs at `20:26:10`, the first delivery fails, and it is retried an
hour later:

**First attempt (fails):**

```
webhook-id: msg_2KWPBgLlAfxdpx2AI54pPJ85f4W
webhook-timestamp: 1667507170          ← attempt time: 20:26:10
```
```json
{ "type": "contact.created", "timestamp": "2022-11-03T20:26:10.344522Z", "data": { "id": "..." } }
```

**Retry one hour later (succeeds):**

```
webhook-id: msg_2KWPBgLlAfxdpx2AI54pPJ85f4W   ← SAME id (idempotency key)
webhook-timestamp: 1667510770          ← attempt time: 21:26:10  (CHANGED)
```
```json
{ "type": "contact.created", "timestamp": "2022-11-03T20:26:10.344522Z", "data": { "id": "..." } }
```
```
                                       ↑ event time in body: UNCHANGED
```

The header advanced by an hour; the body timestamp and the `webhook-id` did not.

#### Which to use, when

- **"Is this request fresh / not a replayed capture?"** → **`webhook-timestamp` header.**
  This is a security check and the library does it for you inside `verify(...)`, rejecting anything
  outside the **±5-minute tolerance** (`Message timestamp too old` / `Message timestamp too new`).
  You normally never read this header yourself, and you must **not** use it as "when the event
  happened" — on a retry it is off by the full retry delay.

- **"When did the event actually happen?"** → **body `timestamp`.**
  Read it only *after* verification succeeds. It is stable across retries, so it is safe to display,
  sort, and compare on.

- **"Have I already processed this event?"** → **neither timestamp — use the `webhook-id` header**
  as an idempotency key. Like the body timestamp, it stays constant across retries.

#### Java example

```java
// 1) Security: verify() checks the HEADER timestamp (replay protection) for you.
webhook.verify(rawBody, headers);   // throws if the webhook-timestamp is outside +/- 5 min

// 2) Idempotency: dedupe on webhook-id (constant across retries), NOT on either timestamp.
String msgId = headers.get("webhook-id").get(0);
if (alreadyProcessed(msgId)) {
    return ResponseEntity.noContent().build();   // 2xx so the producer stops retrying
}

// 3) Business logic: use the BODY timestamp for "when did it happen".
ContactEvent event = objectMapper.readValue(rawBody, ContactEvent.class);
Instant eventOccurredAt = Instant.parse(event.getTimestamp());   // body timestamp
```

> ⚠️ Anti-pattern: using `webhook-timestamp` as the event time, or using either timestamp as the
> idempotency key. On a retry both of those break — the header moves and the event timestamp can
> collide across distinct events. Use `webhook-id` for dedup and the body `timestamp` for event time.

---

## 4. Signature scheme

The signed content concatenates ID, timestamp, and body with full-stops:

```
msg_id.timestamp.payload
```

Example (pretty-printed for readability):

```
msg_2KWPBgLlAfxdpx2AI54pPJ85f4W.1674087231.{
  "type": "contact.created",
  "timestamp": "2022-11-03T20:26:10.344522Z",
  "data": {
    "id": "1f81eb52-5198-4599-803e-771906343485"
  }
}
```

Or, more realistically, with minified JSON (what actually gets sent and signed):

```
msg_2KWPBgLlAfxdpx2AI54pPJ85f4W.1674087231.{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344522Z","data":{"id":"1f81eb52-5198-4599-803e-771906343485"}}
```

> ⚠️ **Sign the exact bytes you send.** Signatures are sensitive to even a stray space. A common
> failure is parsing the body to JSON and re-serializing it before verifying — re-serialization can
> differ and break the signature. Verify against the **raw body bytes**.

### Symmetric vs asymmetric

|                      | Symmetric                                               | Asymmetric                                                            |
| -------------------- | ------------------------------------------------------- | -------------------------------------------------------------------- |
| Signature scheme     | `HMAC-SHA256`                                           | `ed25519`                                                            |
| Signing secret       | Random, 24 bytes (192-bit) to 64 bytes (512-bit)       | Standard ed25519 key pair                                           |
| Secret serialization | base64, prefixed `whsec_`                               | base64, `whsk_` (secret key), `whpk_` (public key)                  |
| Signature identifier | `v1`                                                    | `v1a`                                                               |

A serialized signature is the version id, a comma, then the base64 signature, e.g.
`v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=`.

Prefer asymmetric when you don't control both ends (consumers verify with a non-secret public key).
Use symmetric for speed and ubiquity, treating the key as a secret.

> **This toolkit implements `v2a`, not `v1a`.** The current Standard Webhooks spec defines the Ed25519
> signature identifier as **`v2a`** (it signs the SHA-256 of `endpoint_id.msg_id.timestamp.payload`); the
> older `v1a` above is deprecated and is intentionally **not** implemented. Configure a `whsk_` key on the
> producer and the matching `whpk_` key on the consumer, both with a shared `endpoint-id`, and the scheme is
> selected automatically — see [verification & security → asymmetric signatures](../guides/verification-and-security.md#asymmetric-signatures-ed25519-v2a)
> and [SPEC §8e](./SPEC.md#8e-asymmetric-verification--ed25519-v2a-whpk_) / [§19i](./SPEC.md#19i-asymmetric-signing--ed25519-v2a-whsk_).

---

## 5. Payload samples

Minified body as it travels on the wire (and as it must be signed):

```json
{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344522Z","data":{"id":"1f81eb52-5198-4599-803e-771906343485"}}
```

Full HTTP request:

```http
POST /webhook-endpoint HTTP/1.1
Host: consumer.example.com
Content-Type: application/json
webhook-id: msg_2KWPBgLlAfxdpx2AI54pPJ85f4W
webhook-timestamp: 1674087231
webhook-signature: v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJ+fe/IE0fvV4=

{"type":"contact.created","timestamp":"2022-11-03T20:26:10.344522Z","data":{"id":"1f81eb52-5198-4599-803e-771906343485"}}
```

---

## 6. Java: signing & verification samples (success)

Install the official library from Maven Central.

Maven:

```xml
<dependency>
    <groupId>com.standardwebhooks</groupId>
    <artifactId>standardwebhooks</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation "com.standardwebhooks:standardwebhooks:1.0.0"
```

### API shape (important differences from other languages)

- `new Webhook(String secret)` — the base64 secret, with or without the `whsec_` prefix.
- `new Webhook(byte[] secret)` — a raw key (no base64 decode, no prefix stripping).
- `String sign(String msgId, long timestamp, String payload)` — `timestamp` is **unix seconds**
  as a `long`. Returns e.g. `v1,<base64>`.
- `void verify(String payload, Map<String, List<String>> headers)` — **returns nothing**. It
  succeeds silently and **throws `WebhookVerificationException` on any failure**. (This is unlike
  the JS/Python libs, which return the parsed payload — in Java you parse the body yourself after
  `verify` returns.)
- Header lookup is **case-insensitive**, and header values are a `List<String>` (first value used).

### Sign (producer side)

```java
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;

Webhook webhook = new Webhook("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

String payload = "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344522Z\","
               + "\"data\":{\"id\":\"1f81eb52-5198-4599-803e-771906343485\"}}";
String msgId = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
long timestamp = System.currentTimeMillis() / 1000L; // unix seconds

try {
    String signature = webhook.sign(msgId, timestamp, payload);
    // signature => "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJ+fe/IE0fvV4="
} catch (WebhookSigningException e) {
    // thrown only on a crypto/algorithm error (should not happen with a valid key)
}
```

### Verify (consumer side)

```java
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.util.List;
import java.util.Map;

Webhook webhook = new Webhook("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

Map<String, List<String>> headers = Map.of(
    "webhook-id",        List.of(msgId),
    "webhook-timestamp", List.of(String.valueOf(timestamp)),
    "webhook-signature", List.of(signature)
);

try {
    webhook.verify(payload, headers); // returns void on success
    // SUCCESS: now it's safe to parse `payload` and handle the event
    // e.g. MyEvent event = objectMapper.readValue(payload, MyEvent.class);
} catch (WebhookVerificationException e) {
    // verification failed — reject with a non-2xx status (see §7)
}
```

### Spring Boot controller — use the RAW body

Capture the body as a `String` (or `byte[]`) exactly as received. Do **not** let Jackson parse and
re-serialize it before verifying — re-serialization can change the bytes and break the signature.

```java
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class WebhookController {

    private final Webhook webhook = new Webhook(System.getenv("WEBHOOK_SECRET"));

    @PostMapping("/webhook")
    public ResponseEntity<Void> handle(
            @RequestBody String rawBody,                       // raw, unparsed body
            @RequestHeader Map<String, String> flatHeaders) {

        // Standard Webhooks expects Map<String, List<String>>
        Map<String, List<String>> headers = new java.util.HashMap<>();
        flatHeaders.forEach((k, v) -> headers.put(k, List.of(v)));

        try {
            webhook.verify(rawBody, headers);
        } catch (WebhookVerificationException e) {
            return ResponseEntity.badRequest().build();        // 400 => failure
        }

        // ... verified: parse rawBody and process the event ...
        return ResponseEntity.noContent().build();             // 204 => success (2xx)
    }
}
```

> Tip: `@RequestHeader Map<String, String>` already lowercases header names in Spring and the
> library matches case-insensitively, so `Webhook-Id`, `webhook-id`, etc. all work.

### Servlet / Jakarta EE endpoint

```java
protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String body = req.getReader().lines().collect(java.util.stream.Collectors.joining("\n"));

    Map<String, List<String>> headers = new java.util.HashMap<>();
    java.util.Collections.list(req.getHeaderNames())
        .forEach(name -> headers.put(name, java.util.Collections.list(req.getHeaders(name))));

    try {
        webhook.verify(body, headers);
        resp.setStatus(204);                       // success
    } catch (WebhookVerificationException e) {
        resp.setStatus(400);                       // failure
    }
}
```

### Endpoint verification challenge (extension)

Producers can confirm an endpoint holds the correct secret before delivering webhooks. A GET/POST
arrives carrying a `webhook-challenge` header; echo back a signed response.

```java
String challenge = request.getHeader("webhook-challenge");
if (challenge != null) {
    // Not a webhook delivery — respond to the challenge and return without processing a body
    response.setHeader("webhook-challenge-response",
                       webhook.endpointVerificationResponse(challenge));
    response.setStatus(204);
    return;
}
```

Producer side — verify an endpoint's challenge response:

```java
// Throws WebhookVerificationException if the response is invalid
webhook.verifyEndpointVerificationResponse(challenge, responseHeader);
```

---

## 7. Java: exception / error samples

The Java library defines **three** checked exceptions in `com.standardwebhooks.exceptions`:

| Exception                        | Thrown by                              | When                                                        |
| -------------------------------- | -------------------------------------- | ----------------------------------------------------------- |
| `EmptyWebhookSecretException`    | the constructor                        | the decoded secret is empty                                 |
| `WebhookVerificationException`   | `verify(...)` / `verifyEndpoint...`    | any verification failure (missing/invalid/old signature)    |
| `WebhookSigningException`        | `sign(...)` / `endpointVerification...`| a crypto/algorithm failure while signing                    |

All three extend `Exception` (checked), so the compiler forces you to handle them.

### Exact messages raised by `verify(String, Map)`

| Condition                                             | Message (`WebhookVerificationException`) |
| ---------------------------------------------------- | ---------------------------------------- |
| Any of `webhook-id`/`-timestamp`/`-signature` absent | `Missing required headers`               |
| `webhook-timestamp` not parseable as a `long`        | `Invalid Signature Headers`              |
| Timestamp older than the 5-minute tolerance          | `Message timestamp too old`              |
| Timestamp newer than the 5-minute tolerance          | `Message timestamp too new`              |
| No signature in the (space-delimited) list matches   | `No matching signature found`            |

Constructor message:

| Condition             | Message (`EmptyWebhookSecretException`)   |
| --------------------- | ----------------------------------------- |
| Empty decoded secret  | `Webhook secret should not be empty`      |

> The replay tolerance is **5 minutes** (300 s), and comparisons use a constant-time
> `MessageDigest.isEqual`, so verification is not vulnerable to timing attacks.

### Catching each failure

```java
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.EmptyWebhookSecretException;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.util.List;
import java.util.Map;

// 0) Construction error — empty secret
try {
    new Webhook("");                                   // or "whsec_"
} catch (EmptyWebhookSecretException e) {
    System.out.println(e.getMessage());                // "Webhook secret should not be empty"
}

Webhook webhook = new Webhook("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
String payload = "{\"type\":\"contact.created\"}";
String msgId = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
String signature = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJ+fe/IE0fvV4=";

// 1) Missing headers
try {
    webhook.verify(payload, Map.of());                 // no webhook-* headers
} catch (WebhookVerificationException e) {
    System.out.println(e.getMessage());                // "Missing required headers"
}

// 2) Bad timestamp format
try {
    webhook.verify(payload, Map.of(
        "webhook-id",        List.of(msgId),
        "webhook-timestamp", List.of("not-a-number"),
        "webhook-signature", List.of(signature)));
} catch (WebhookVerificationException e) {
    System.out.println(e.getMessage());                // "Invalid Signature Headers"
}

// 3) Replay attack — stale timestamp (older than 5 minutes)
try {
    webhook.verify(payload, Map.of(
        "webhook-id",        List.of(msgId),
        "webhook-timestamp", List.of("1600000000"),    // far in the past
        "webhook-signature", List.of(signature)));
} catch (WebhookVerificationException e) {
    System.out.println(e.getMessage());                // "Message timestamp too old"
}

// 4) Future timestamp (more than 5 minutes ahead)
try {
    long future = System.currentTimeMillis() / 1000L + 3600;
    webhook.verify(payload, Map.of(
        "webhook-id",        List.of(msgId),
        "webhook-timestamp", List.of(String.valueOf(future)),
        "webhook-signature", List.of(signature)));
} catch (WebhookVerificationException e) {
    System.out.println(e.getMessage());                // "Message timestamp too new"
}

// 5) Tampered body / wrong secret — signature mismatch
try {
    long now = System.currentTimeMillis() / 1000L;
    webhook.verify("{\"tampered\":true}", Map.of(
        "webhook-id",        List.of(msgId),
        "webhook-timestamp", List.of(String.valueOf(now)),
        "webhook-signature", List.of(signature)));
} catch (WebhookVerificationException e) {
    System.out.println(e.getMessage());                // "No matching signature found"
}
```

### Signing failure

```java
import com.standardwebhooks.exceptions.WebhookSigningException;

try {
    String sig = webhook.sign(msgId, System.currentTimeMillis() / 1000L, payload);
} catch (WebhookSigningException e) {
    // Only on an underlying JCE error (InvalidKeyException / NoSuchAlgorithmException).
    // With a valid HMAC-SHA256 key this normally never fires.
}
```

### Returning the error to the producer

```http
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Invalid signature
```

A `4xx`/`5xx` response is treated as a delivery **failure** by the producer and will be retried
(see [§8](#8-delivery-success--failure-http-status-handling) and [§9](#9-retry-schedule)).

---

## 8. Delivery success & failure (HTTP status handling)

A delivery is **successful** only on a `2xx` (200–299) response. Everything else is a failure:
non-2xx codes, request timeouts, connection resets, etc.

Suggested status-code handling on the producer side:

| Status                               | Treatment                                                              |
| ------------------------------------ | --------------------------------------------------------------------- |
| `2xx`                                | **Success.**                                                          |
| `3xx`                                | Failure. Don't follow redirects — update the webhook URL instead.     |
| `410 Gone`                           | Consumer no longer wants webhooks — **disable the endpoint**, stop sending. |
| `429 Too Many Requests`              | Rate-limited — throttle further requests.                             |
| `502 Bad Gateway` / `504 Gateway Timeout` | Server under load — throttle requests.                           |
| Any other status                     | Treat as a failure (and retry).                                      |

If a response carries a `retry-after` header (e.g. `503 Service Unavailable`), honor it when
scheduling the next attempt.

**Request timeout:** recommend a consumer processing timeout of **15–30 seconds**.

> **This toolkit's scope.** The producer here treats `410 Gone` as a generic fatal `4xx` (it is
> abandoned, dead-lettered via the `WebhookDeliveryListener` hook, and rethrown) rather than
> auto-disabling an endpoint — endpoint-lifecycle state is outside the no-storage design; see
> [SPEC §20.2](./SPEC.md#202-what-is-not-guaranteed-the-no-storage-boundary). It **does** honor
> `Retry-After` on a retryable `429`/`503`, applying it as a backoff *floor* —
> `max(exponential + jitter, Retry-After)`, clamped to `max-backoff` — since that needs no storage;
> see [SPEC §20.6/A1](./SPEC.md#206-spec-alignment-backlog).

### Example consumer responses

Success:

```http
HTTP/1.1 200 OK
```

Permanent opt-out (producer should disable the endpoint):

```http
HTTP/1.1 410 Gone
```

Backpressure (producer should throttle, honor retry-after):

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 120
```

---

## 9. Retry schedule

Retries are essential for reliability. Use exponential backoff spanning multiple days, with random
jitter. After prolonged consistent failure, notify the consumer out-of-band (e.g. email) and
consider disabling the endpoint.

Example retry schedule:

| Delay       | Time since start |
| ----------- | ---------------- |
| Immediately | 00:00:00         |
| 5 seconds   | 00:00:05         |
| 5 minutes   | 00:05:05         |
| 30 minutes  | 00:35:05         |
| 2 hours     | 02:35:05         |
| 5 hours     | 07:35:05         |
| 10 hours    | 17:35:05         |
| 14 hours    | 31:35:05         |
| 20 hours    | 51:35:05         |
| 24 hours    | 75:35:05         |

---

## 10. Security checklist

- ✅ **Verify authenticity** of every webhook — they are HTTP requests from an unknown source.
- ✅ **Constant-time comparison** for symmetric signatures (avoid timing attacks / signing oracles).
- ✅ **Battle-tested crypto library** for asymmetric signatures; keep it up to date.
- ✅ **Replay protection** — reject timestamps outside the tolerance window (default 5 minutes).
- ✅ **Idempotency** — use `webhook-id` as an idempotency key (e.g. store IDs in Redis ~5 min).
- ✅ **Unique keys per endpoint** (per endpoint/customer for asymmetric). Never reuse across customers.
- ✅ **Trust list** of accepted public keys — never trust a key supplied in the request itself.
- ✅ **Enforce HTTPS** — signatures verify authenticity but don't encrypt the payload.
- ✅ **Protect against SSRF** — proxy outbound webhook calls (e.g. Stripe's `smokescreen`) and run
  webhook workers in an isolated subnet that can't reach internal services.
- ✅ **Static source IPs** — provide a fixed IP list for consumers behind firewalls (optional).

---

## 11. Tracing & logging (consumer side)

> The Standard Webhooks spec does **not** mandate any tracing or logging mechanism — it only
> defines the headers (`webhook-id`, `webhook-timestamp`, `webhook-signature`) and the signature
> scheme. Everything below is framework-neutral guidance for observing a **consumer** (a service
> that receives and verifies webhooks). The spec does, however, recommend that producers give
> consumers "visibility into failures" — good consumer-side logging is the other half of that.

### Correlation: what to key everything on

The single most important rule: **use `webhook-id` as your correlation / trace key**, not a random
per-request id.

- `webhook-id` is **stable across retries** (same event redelivered keeps the same id), so every
  delivery attempt for one event shares one correlation key — you can see the whole retry history
  in your logs.
- It doubles as your **idempotency key** (see §3a), so the same value ties together logging,
  tracing, and dedup.
- Do **not** correlate on `webhook-timestamp` (it changes every retry) or on the body `timestamp`
  (it can collide across distinct events).

A typical correlation context to attach to every log line and span:

| Field              | Source                       | Notes                                           |
| ------------------ | ---------------------------- | ----------------------------------------------- |
| `webhook.id`       | `webhook-id` header          | Primary correlation key; stable across retries. |
| `webhook.event_type` | body `type`                | Only read **after** verification succeeds.      |
| `webhook.timestamp`  | body `timestamp`           | Event occurrence time (for lag calculations).   |
| `attempt.timestamp`  | `webhook-timestamp` header | Attempt time; log the delivery lag vs. now.     |
| `verification.result`| your code                  | `ok` / `failed:<reason>`.                        |

### Distributed tracing

Treat an inbound webhook as the **start of a trace** on your side. The producer and consumer are
separate systems, so unless the producer forwards trace context, you begin a new trace.

- **Span per delivery attempt.** Name it something like `webhook.receive` and set the correlation
  fields above as span attributes.
- **Child spans** for the meaningful steps: `verify`, `deserialize`, `handle` (your business
  logic), and any downstream calls (DB, queue, other APIs). This makes it obvious whether latency
  or failure is in verification vs. processing.
- **Trace-context propagation.** If a producer includes a standard trace header (e.g. W3C
  `traceparent`) you may continue that trace instead of starting a fresh one — but only after you
  decide to trust it. Trace headers are **not** covered by the webhook signature, so treat them as
  untrusted hints: use them to link traces, never for authorization or security decisions.
- **Record the outcome on the span.** Mark the span as error for a verification failure and set an
  attribute such as `verification.result = failed:message_timestamp_too_old` so failures are
  queryable without reading logs.
- **Async processing.** If you return `2xx` quickly and process on a background worker/queue (a
  common and recommended pattern), propagate the `webhook-id` (and trace context) through the queue
  message so the worker's span links back to the receive span.

Suggested span shape:

```
span: webhook.receive
  attrs: webhook.id, attempt.timestamp, http.status_code, verification.result
  ├─ span: webhook.verify        (error=true if WebhookVerificationException)
  ├─ span: webhook.deserialize   (attrs: webhook.event_type, webhook.timestamp)
  └─ span: webhook.handle        (your logic + downstream child spans)
```

### What to log (and when)

Log at the boundaries of the receive flow, always carrying `webhook.id`:

1. **On receipt** (before verifying): `received webhook` with `webhook.id`, `attempt.timestamp`,
   source IP, and payload size. Not the body yet.
2. **On verification success**: `webhook verified` with `webhook.event_type` and the computed
   **delivery lag** (`now − attempt.timestamp`) and **event lag** (`now − body.timestamp`). High lag
   is an early signal of producer backlog or your own slowness.
3. **On verification failure**: `webhook verification failed` at **WARN**, with the exact reason
   (`Missing required headers`, `Invalid Signature Headers`, `Message timestamp too old/too new`,
   `No matching signature found`) and the `webhook.id` if present. These are security-relevant —
   repeated failures from one source may indicate spoofing/replay attempts.
4. **On idempotent skip**: `webhook already processed` at DEBUG/INFO with `webhook.id`.
5. **On handling outcome**: success or the handler exception, with duration.
6. **On the HTTP response**: the status code you returned (drives the producer's retry behavior,
   see §8).

Use **structured** (key/value or JSON) logs so `webhook.id`, `event_type`, and `verification.result`
are queryable fields, not free text.

### Log levels — suggested mapping

| Event                                         | Level  |
| --------------------------------------------- | ------ |
| Webhook received / verified / handled OK      | INFO   |
| Idempotent duplicate skipped                  | DEBUG / INFO |
| Verification failed (bad sig, old/new ts, missing headers) | WARN |
| Handler threw / downstream dependency failed  | ERROR  |
| Repeated verification failures from one source | WARN (consider alerting) |

### What NOT to log (security & privacy)

- ❌ **Never log the signing secret** (`whsec_…`) or the raw `webhook-signature` value at anything
  but the deepest debug level — a leaked signature + body enables replay within the tolerance
  window, and a leaked secret is total compromise.
- ❌ **Avoid logging full payload bodies by default** — they often contain PII or sensitive event
  data. Log the `type`, `webhook.id`, and size; gate full-body logging behind a debug flag and
  redact sensitive fields.
- ❌ Don't log the decoded key material or any header that could be replayed.
- ✅ Safe to log freely: `webhook-id`, `webhook-timestamp`, `type`, body `timestamp`, payload size,
  verification result, HTTP status, timing.

### Metrics worth emitting

Complement traces/logs with counters and timers:

- `webhook.received.count` (tagged by `event_type`).
- `webhook.verification.failed.count` (tagged by `reason`) — alert on spikes.
- `webhook.duplicate.count` (idempotent skips).
- `webhook.handle.duration` (timer) — watch against your 15–30 s processing budget (§8).
- `webhook.delivery.lag` and `webhook.event.lag` (gauges/histograms) — surface producer backlog.

### End-to-end: where the two timestamps show up

Pulling §3a into observability terms:

- **Delivery lag** = `now − webhook-timestamp` (header). Measures how long the *attempt* took to
  reach you; grows with retries and producer backlog.
- **Event lag** = `now − body.timestamp`. Measures how stale the *event* is end-to-end. On a retried
  message this is naturally larger than the delivery lag.

Logging both makes it easy to tell "the producer is slow to deliver" apart from "this is just an old
event being retried."

---

## 12. Reference libraries

Official SDKs implementing this spec:

| Language              | Package / Repo                                                                 |
| --------------------- | ----------------------------------------------------------------------------- |
| **Java / Kotlin**     | `com.standardwebhooks:standardwebhooks` (Maven Central) · [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/java) — **requires Java 1.8+** |
| Python                | `standardwebhooks` · [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/python) |
| JavaScript/TypeScript | `standardwebhooks` · [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/javascript) |
| Rust                  | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/rust) |
| Go                    | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/go) |
| Ruby                  | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/ruby) |
| PHP                   | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/php) |
| C#                    | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/csharp) |
| Elixir                | [repo](https://github.com/standard-webhooks/standard-webhooks/tree/main/libraries/elixir) |

**Tools**

- [Verify Webhook](https://www.standardwebhooks.com/verify) — sign, verify, and debug signatures.
- [Simulate Request](https://www.standardwebhooks.com/simulate) — simulate a Standard Webhooks message.
- [Receiving-webhooks AI skill](https://github.com/standard-webhooks/standard-webhooks/blob/main/skills/receiving-webhooks/).
