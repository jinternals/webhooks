# Webhooks Toolkit — Design Spec

> A Standard Webhooks–compliant **toolkit** for Spring Boot, shipped as a four-module reactor:
> a shared **core**, a **consumer** that receives/verifies/dispatches inbound webhooks, an
> annotation-driven **producer** that signs and sends outbound webhooks, and an independent
> standalone **sample** app. The consumer deserializes the payload **by `type`** (via a `@WebhookType`
> annotation) and dispatches to typed handler methods; the producer derives the same `@WebhookType` to build,
> sign, and POST the envelope.
>
> **Scope: NO storage anywhere — no delivery history, no attempt log, no retry persistence.** The
> producer retries transient failures *in process* (bounded attempts, backoff on the calling thread;
> §19b) but never spools to a queue or disk; a delivery that exhausts its attempts is surfaced to the
> caller, and the consumer returns the right status code. Durable, cross-restart retry remains the
> sender's responsibility (plug a queue in behind `WebhookTransport`).
>
> Built with the config-driven and annotation-driven patterns from
> [`jinternals/dynamic-rest-api`](https://github.com/jinternals/dynamic-rest-api) and
> [`jinternals/jinternals-event-bus`](https://github.com/jinternals/jinternals-event-bus).
>
> Companion document: [`STANDARD_WEBHOOKS.md`](./STANDARD_WEBHOOKS.md) — the protocol reference
> (headers, signature scheme, payloads, errors, tracing/logging).

---

## Table of contents

1. [Goals & scope](#1-goals--scope)
2. [Prior art we are reusing](#2-prior-art-we-are-reusing)
3. [Receive flow](#3-receive-flow)
4. [The `@WebhookType` annotation & event model](#4-the-webhooktype-annotation--event-model)
5. [Serialization: type → class registry](#5-serialization-type--class-registry)
6. [Configuration model (config-driven endpoints)](#6-configuration-model-config-driven-endpoints)
   - [6a. Per-type payload validation](#6a-per-type-payload-validation)
7. [Runtime endpoint registration](#7-runtime-endpoint-registration)
8. [Verification (Standard Webhooks)](#8-verification-standard-webhooks)
   - [8a. Secret source & rotation (`SecretProvider`)](#8a-secret-source--rotation-secretprovider)
   - [8b. Replay window (timestamp freshness)](#8b-replay-window-timestamp-freshness)
   - [8e. Asymmetric verification — Ed25519 `v2a` (`whpk_`)](#8e-asymmetric-verification--ed25519-v2a-whpk_)
9. [Webhook metadata object](#9-webhook-metadata-object)
   - [9a. Field-level redaction — `@Redact`](#9a-field-level-redaction--redact)
   - [9b. Correlation & trace context (app-owned)](#9b-correlation--trace-context-app-owned)
10. [Typed handler dispatch](#10-typed-handler-dispatch)
11. [Idempotency without storage](#11-idempotency-without-storage)
12. [Error handling & status codes](#12-error-handling--status-codes)
13. [Observability](#13-observability)
14. [Module / package layout](#14-module--package-layout)
    - [14a. Production hardening](#14a-production-hardening)
15. [Sync vs async handling](#15-sync-vs-async-handling)
16. [Quality gate — coverage & mutation testing](#16-quality-gate--coverage--mutation-testing)
17. [Open questions](#17-open-questions)
18. [Performance](#18-performance)
19. [Producer — annotation-driven sending](#19-producer--annotation-driven-sending)
    - [19a. Secret rotation (blue/green)](#19a-secret-rotation-bluegreen)
    - [19b. In-process retry (no persistence)](#19b-in-process-retry-no-persistence)
    - [19c. HTTP timeouts](#19c-http-timeouts)
    - [19d. Destination URL guard (SSRF)](#19d-destination-url-guard-ssrf)
    - [19e. Dependency vulnerability scanning](#19e-dependency-vulnerability-scanning)
    - [19f. Log-safe destination URLs](#19f-log-safe-destination-urls)
    - [19g. Transport authentication (bearer tokens / OAuth2)](#19g-transport-authentication-bearer-tokens--oauth2)
    - [19i. Asymmetric signing — Ed25519 `v2a` (`whsk_`)](#19i-asymmetric-signing--ed25519-v2a-whsk_)
20. [Reliability & delivery guarantees](#20-reliability--delivery-guarantees)
    - [20.1 What *is* guaranteed (in-process)](#201-what-is-guaranteed-in-process)
    - [20.2 What is *not* guaranteed (the no-storage boundary)](#202-what-is-not-guaranteed-the-no-storage-boundary)
    - [20.3 Failure modes at a glance](#203-failure-modes-at-a-glance)
    - [20.4 How to make a deployment reliable](#204-how-to-make-a-deployment-reliable)
    - [20.5 Failure hooks (dead-letter capture)](#205-failure-hooks-dead-letter-capture)
    - [20.6 Spec-alignment backlog](#206-spec-alignment-backlog)

---

## 1. Goals & scope

Build a reusable **webhook listener** (library + demo app) that is Standard Webhooks compliant and
follows the house style of the `jinternals` projects.

**In scope:**

- **Receive** inbound webhook HTTP POSTs.
- **Verify** authenticity (HMAC-SHA256 signature + replay-window check) per Standard Webhooks.
- **Config-driven endpoints** — listen paths declared in `application.yaml` and registered at
  runtime, like `dynamic-rest-api` (no controller-per-endpoint).
- **Annotation-driven deserialization** — `@WebhookType("...")` maps the webhook `type` to a Java class,
  so the body is parsed by type without a switch (modelled on `@EventMapper`).
- **Typed dispatch** — route each verified event to a `@WebhookHandler` method.
- **Metadata to handlers** — handlers receive a `WebhookMetadata` object (type, timestamps,
  `webhook-id`, headers, …) alongside the typed event (see §9).

> **Note.** §1–§18 describe the **consumer** (the original scope of this document). The **producer**
> added in the four-module restructure is specified in [§19](#19-producer--annotation-driven-sending);
> both sides share [`webhooks-core`](#14-module--package-layout).

**Out of scope (explicitly):**

- ❌ No storage of any kind — no delivery history, no attempt log, no database (both sides).
- ❌ No retry persistence — the producer retries transient failures *in process* only (bounded,
  no queue/disk/scheduler; §19b) and throws `WebhookDeliveryException` once attempts are exhausted; the
  consumer returns the right status code. Durable cross-restart retry stays the sender's job (see §19).
- ❌ No asymmetric (`ed25519`) signatures (v1).

> Idempotency normally needs a store (Redis/DB). Since we want **no storage**, idempotency is
> handled per §10 (optional, in-memory best-effort, off by default). The verifier's replay window
> (§8b) protects against replay attacks without any storage when enabled — though it ships **off by
> default**, so enable it for internet-facing endpoints.

---

## 2. Prior art we are reusing

| Pattern | Source repo | What we take |
| --- | --- | --- |
| Config-driven endpoints registered at runtime | `dynamic-rest-api` → `DynamicEndpointRegistry` | Register listen paths from `application.yaml` via `RequestMappingHandlerMapping.registerMapping(...)`. |
| `@ConfigurationProperties` endpoint list | `dynamic-rest-api` → `DynamicEndpointProperties` | A typed `webhooks.*` properties tree. |
| Argument resolver injecting per-request config | `dynamic-rest-api` → `SchemaArgumentResolver` | Resolve the signing secret for the matched path and inject it. |
| `ProblemDetail` + `@ControllerAdvice` errors | `dynamic-rest-api` → `GlobalExceptionHandler` | Uniform RFC-7807 error responses. |
| Per-type payload validation | `dynamic-rest-api` → `SchemaValidationService` | Bean Validation constraints on the `@WebhookType` class, enforced on the deserialized event (§6a). |
| `@EventMapper("type")` on event classes | `jinternals-event-bus` → `EventMapper` | Our `@WebhookType("...")` annotation. |
| Classpath type → class scan | `jinternals-event-bus` → `EventUtils.getTypeMapping` | Build the `type → Class` registry at startup. |
| `@EventListener` handler method marker | `jinternals-event-bus` → `EventListener` | Our `@WebhookHandler` method annotation. |
| Event headers constants | `jinternals-event-bus` → `EventHeaders` | Mapped onto Standard Webhooks headers. |

> `dynamic-rest-api` targets **Spring Boot 3.3 / Java 21**. We target Java 21 too; the shipped parent is
> Spring Boot 3.5.16. Payload validation uses Bean Validation (Hibernate Validator via
> `spring-boot-starter-validation`) rather than a JSON-schema engine.

---

## 3. Receive flow

```
   Incoming HTTP POST  (webhook-id / webhook-timestamp / webhook-signature + JSON body)
            │
            ▼
   WebhookReceiveController.handle(...)        ← single generic controller, registered per config path
            │
            ▼
   WebhookVerifier.verify(secret, RAW body, headers)      ← Standard Webhooks (throws on failure)
            │ ok
            ▼
   (optional) IdempotencyGuard.tryBegin(webhook-id)?      ← reserve; in-memory best-effort, off by default
            │ reserved (new)                               (commit on success, release on failure — §11)
            ▼
   endpoint.accepts(envelope.type)?                       ← per-URL type allow-list (§6); empty = all
            │ yes
            ▼
   WebhookTypeRegistry.classOf(envelope.type)  ──►  deserialize envelope.data into that @WebhookType class
            │                                  (strict reader: unknown fields → 400, §6a)
            ▼
   PayloadValidator.validate(type, event)                 ← Bean Validation on the @WebhookType object (§6a)
            │ valid
            ▼
   build WebhookMetadata  (type, timestamps, webhook-id, headers)             ← §9
            │
            ▼
   WebhookDispatcher.dispatch(event, metadata)   ──►  @WebhookHandler method
            │                                  (no handler for a known type → 500, §10/§12)
            ▼
   HTTP 204 No Content   (2xx => the sender considers delivery successful)
```

No state is written anywhere. The only thing we return to the sender is a **status code**, which is
how the sender decides whether to retry (see `STANDARD_WEBHOOKS.md` §8).

---

## 4. The `@WebhookType` annotation & event model

Modelled on `@EventMapper`. The value is the Standard Webhooks `type` string.

```java
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebhookType {
    /** The Standard Webhooks event type, e.g. "contact.created". */
    String value();
}
```

Example event class — this maps to the webhook payload's `data`:

```java
@WebhookType("contact.created")
public class ContactCreated {
    private String id;
    private String fullName;
    private String email;
    // getters / setters ...
}
```

On-the-wire envelope (Standard Webhooks shape — see `STANDARD_WEBHOOKS.md` §2):

```json
{
  "type": "contact.created",
  "timestamp": "2022-11-03T20:26:10.344522Z",
  "data": { "id": "1f81eb52-...", "fullName": "John Smith", "email": "j@x.com" }
}
```

Envelope POJO used only for routing (before we know the concrete type):

```java
public class Envelope {
    private String type;                 // selects the @WebhookType class
    private String timestamp;            // event time (ISO 8601) — see STANDARD_WEBHOOKS.md §3a
    private JsonNode data;               // deserialized into the @WebhookType class after routing
    // getters / setters ...
}
```

---

## 5. Serialization: type → class registry

Adapted from `EventUtils.getTypeMapping`. At startup, scan a base package for `@WebhookType` classes and
build the lookup map.

The registry itself is a **pure, immutable map holder** that lives in `webhooks-core` and carries no
scanning machinery:

```java
public class WebhookTypeRegistry {

    private final Map<String, Class<?>> byType;   // "contact.created" -> ContactCreated.class

    public WebhookTypeRegistry(Map<String, Class<?>> byType) {
        this.byType = Map.copyOf(byType);
    }

    public Class<?> classOf(String type) { return byType.get(type); }   // null => unknown type
}
```

Classpath scanning is a **consumer-side concern**, so it lives in the consumer-autoconfigure module as a
separate `WebhookTypeScanner`. It uses Spring's own `ClassPathScanningCandidateComponentProvider` (ASM-based
metadata reading — no extra dependency, and only matching classes are loaded). The auto-configuration calls it
to build the registry from the host application's auto-configuration packages:

```java
public final class WebhookTypeScanner {

    private WebhookTypeScanner() {}   // static utility; not instantiable

    public static WebhookTypeRegistry scan(Collection<String> basePackages) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(WebhookType.class, false)); // direct annotation only
        Map<String, Class<?>> byType = new LinkedHashMap<>();
        for (String pkg : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                Class<?> c = ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader);
                // putIfAbsent; two DIFFERENT classes claiming one type string -> IllegalStateException
            }
        }
        return new WebhookTypeRegistry(byType);
    }

    public static WebhookTypeRegistry scan(String basePackage) {
        return scan(List.of(basePackage));
    }
}
```

Only **concrete, independent** classes (top-level or `static` nested) directly annotated with `@WebhookType`
are discovered — the kinds Jackson can instantiate. Base packages include their sub-packages.

Keeping the scanner out of core means a producer (which never scans) carries no scanning code, and the core
artifact stays free of classpath-scanning weight.

Consumer flow: read `envelope.type` → `classOf(type)` → Jackson `convertValue(envelope.data, clazz)`.

**One URL, one or many types.** The registry is global (`type → Class`), and the envelope's `type`
field — not the URL — selects the class. So a **single listen path naturally accepts multiple event
types**: `POST /webhooks/crm` can receive `contact.created`, `contact.updated`, `contact.deleted`,
and each is routed to its own `@WebhookType` class and handler. A single-type endpoint is just the special
case where only one `type` ever arrives there. An endpoint may optionally **constrain** which types
it accepts via a per-endpoint allow-list (see §6); by default it accepts every known `@WebhookType`.

**Unknown type policy** (configurable):

- `ack-and-ignore` (default): log WARN, return `204` so the sender stops retrying an event we don't
  model.
- `reject`: return `400`.

> ⚠️ Always verify the signature against the **raw bytes first**, then parse. Never re-serialize
> the body before verifying (see `STANDARD_WEBHOOKS.md` §4).

---

## 6. Configuration model (config-driven endpoints)

Mirrors `DynamicEndpointProperties`. Consumer-only.

**A single URL can accept one *or* many event types.** The envelope's `type` selects the handler
(see §5), so one path routes however many types arrive there. The optional per-endpoint `types`
allow-list lets you decide the scope of each URL:

- **omit `types`** — the endpoint accepts **every** known `@WebhookType` (the common, fully-multi case);
- **one entry** — a **single-type** endpoint (reject everything else per the unknown-type policy);
- **many entries** — a **multi-type** endpoint limited to an explicit set.

```yaml
webhooks:
  consumer:
    unknown-type-policy: ack-and-ignore         # or: reject
    endpoints:
      # Multi-type endpoint — one URL, many types (allow-list of 3)
      - path: /webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}           # whsec_...
        types:
          - contact.created
          - contact.updated
          - contact.deleted

      # Single-type endpoint — only one type accepted here
      - path: /webhooks/billing/invoice-paid
        secret: ${BILLING_WEBHOOK_SECRET}
        types:
          - invoice.paid

      # No allow-list — accepts ALL known @WebhookType events on this one URL
      - path: /webhooks/firehose
        secret: ${FIREHOSE_WEBHOOK_SECRET}

server:
  port: 9091
```

Backing properties (abbreviated):

```java
@Data
@ConfigurationProperties("webhooks")
public class WebhookProperties {
    private Consumer consumer = new Consumer();

    @Data public static class Consumer {
        private String basePackage;
        private UnknownTypePolicy unknownTypePolicy = UnknownTypePolicy.ACK_AND_IGNORE;
        @NestedConfigurationProperty private List<Endpoint> endpoints = new ArrayList<>();
    }
    @Data public static class Endpoint {
        private String path;
        private String secret;          // whsec_... HMAC secret for this endpoint
        private List<String> types = new ArrayList<>();   // empty = accept all known types

        /** True when this endpoint accepts the given event type (empty list = accept all). */
        public boolean accepts(String type) { return types.isEmpty() || types.contains(type); }
    }
}
```

At receive time the controller checks `endpoint.accepts(type)` **before** dispatch. A type outside
the allow-list is treated as an unknown type *for that endpoint* and handled by the configured
unknown-type policy (`ack-and-ignore` → `204`, or `reject` → `400`).

### 6a. Per-type payload validation

Payload validation uses **Jakarta Bean Validation** (Hibernate Validator, via
`spring-boot-starter-validation`). The `@WebhookType` class **is** the schema: validation constraints are
declared with annotations on its fields (`@NotBlank`, `@Positive`, `@Size`, `@Email`, …) and enforced
on the deserialized event object. Because the envelope's `type` selects the `@WebhookType` class, this works
naturally with multi-type and single-type endpoints alike — and there is **no per-type schema config**
to maintain.

```java
@WebhookType("invoice.paid")
@Data
public class InvoicePaid {
    @NotBlank private String invoiceId;
    @NotBlank private String customerId;
    @Positive private BigDecimal amount;
    @Size(min = 3, max = 3) private String currency;
}
```

**Verify vs. validate** — two independent checks, in this order:

| Step | Question | Mechanism |
| --- | --- | --- |
| **Verify** (§8) | "Is this really from the sender?" | HMAC signature + replay window (security) |
| **Validate** (§6a) | "Is the payload well-formed?" | Bean Validation on the `@WebhookType` object (data quality) |

The `data` node is deserialized with a **strict** reader that rejects unknown JSON fields, so a payload
carrying properties the `@WebhookType` class does not declare yields a `400`. Validation then runs **after**
verification and deserialization, and **before** dispatch. Every deserialized event is validated; a
`@WebhookType` class that declares no constraints trivially passes.

```java
public void validate(String type, Object event) {
    Set<ConstraintViolation<Object>> violations = validator.validate(event);
    if (!violations.isEmpty()) {
        throw new PayloadValidationException(type, errors);   // → 400 with errors[]
    }
}
```

On failure the `GlobalExceptionHandler` returns a `400` RFC-7807 `ProblemDetail` carrying the
`eventType` and an `errors[]` array (see §12).

---

## 7. Runtime endpoint registration

Adapted from `DynamicEndpointRegistry`: at `@PostConstruct`, register one POST mapping per
configured path, all pointing at a single generic controller method.

```java
@Component
@RequiredArgsConstructor
public class WebhookEndpointRegistry {

    private final RequestMappingHandlerMapping handlerMapping;
    private final WebhookReceiveController controller;
    private final WebhookProperties properties;

    @PostConstruct
    public void registerEndpoints() {
        Method handle = ReflectionUtils.findMethod(WebhookReceiveController.class,
            "handle", HttpServletRequest.class, String.class, Map.class);

        for (Endpoint ep : properties.getConsumer().getEndpoints()) {
            RequestMappingInfo info = RequestMappingInfo
                .paths(ep.getPath())
                .methods(RequestMethod.POST)
                .build();
            handlerMapping.registerMapping(info, controller, handle);
        }
    }
}
```

An argument resolver (like `SchemaArgumentResolver`) matches the request path to its `Endpoint` and
injects the right **secret** (and optional schema), so the controller stays generic across all
configured paths.

---

## 8. Verification (Standard Webhooks)

`WebhookVerifier` is a one-method SPI; the shipped `DefaultWebhookVerifier` selects the Standard Webhooks
scheme **per secret, by prefix** — symmetric `v1` (HMAC) for `whsec_` secrets and asymmetric `v2a`
(Ed25519) for `whpk_` public keys (§8e) — so an endpoint can verify either, or a mix of both during a
scheme migration, with no extra flag.

```java
public interface WebhookVerifier {
    /** Throws WebhookVerificationException if invalid (bad sig / replay / missing headers). */
    void verify(List<String> secrets, String endpointId, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException;
}
```

`DefaultWebhookVerifier` owns the shared skeleton once — the empty-secret check, the case-insensitive
`webhook-id` / `webhook-timestamp` / `webhook-signature` parse, and the single replay-window check
(§8b) — then routes each candidate secret by prefix to the HMAC engine (`HmacWebhookVerifier`) or the
Ed25519 engine (`Ed25519WebhookVerifier`). The webhook is accepted if **any** configured secret verifies
**any** space-separated signature in the header, which is the spec's trust-list / rotation model and
spans schemes (so a dual-signed `v1,… v2a,…` delivery is accepted by whichever key the endpoint holds).

The symmetric HMAC math itself is delegated to the official `com.standardwebhooks:standardwebhooks`
library (`Webhook.sign` → `v1,<base64>`; exact API and error messages in `STANDARD_WEBHOOKS.md` §6–7),
so HMAC-SHA256, the `msg_id.timestamp.payload` scheme, and `whsec_` secret decoding all come from the
audited code. The wrapper adds, on top of it: **multi-secret acceptance** for rotation (§8a),
**multi-signature acceptance** (space-delimited values, for dual-signing senders), constant-time
comparison (`MessageDigest.isEqual`), and a **configurable, enabled-by-default replay window** (§8b). The
library's own `verify` is not used because it bundles a fixed, non-tunable ±5-minute timestamp check,
and because it has no asymmetric (`v2a`) support at all.

---

### 8a. Secret source & rotation (`SecretProvider`)

Where the signing secret for an endpoint *comes from* is a separate concern from *how* a body is
verified. We split it behind a one-method SPI so the default (static config) is trivial but a
secrets manager, database, or rotating cache can be dropped in without touching the verifier.

```java
public interface SecretProvider {
    /** Candidate secret(s) for this endpoint, newest first. Never null; empty = fail closed. */
    List<String> secretsFor(WebhookProperties.Endpoint endpoint);
}
```

**Default — `ConfigSecretProvider`.** Resolves secrets straight from configuration: the endpoint's
primary `secret` first, then any `additional-secrets`. It is `@ConditionalOnMissingBean`, so
publishing your own `@Bean SecretProvider` replaces it.

```yaml
webhooks:
  consumer:
    endpoints:
      - path: /webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}           # primary
        additional-secrets:                      # accepted during a rotation window
          - ${CRM_WEBHOOK_SECRET_PREVIOUS}
```

**Rotation is zero-downtime.** `WebhookVerifier.verify` takes the *list* of candidate secrets and
succeeds if the signature validates against **any** of them, only throwing when none match. The
rotation dance is therefore:

1. Add the new secret alongside the old one (new first, old in `additional-secrets`). Both are
   accepted.
2. Tell the sender to switch to the new secret.
3. Once traffic is confirmed on the new secret, drop the old one.

No request is ever rejected mid-rotation. Blank entries are discarded so a half-filled rotation
window can never silently leave an endpoint with weaker (or no) verification.

> **Fail closed.** An empty secret list is a hard failure, not a bypass: `verify` throws
> `WebhookVerificationException("No webhook secret configured for this endpoint")`. A misconfigured
> endpoint rejects everything rather than accepting unsigned traffic.

---

### 8b. Replay window (timestamp freshness)

A signature proves a request is *authentic* but not *fresh*: an attacker who records one valid delivery
can re-send the identical bytes forever and every signature will still verify. Standard Webhooks closes
this gap with the **replay window** — the `webhook-timestamp` (covered by the signature) is compared to
the server clock, and a request outside an allowed skew is rejected. This is what lets a stateless
consumer resist replay without a seen-ids store.

**The window is a separate, injectable concern.** The upstream `com.standardwebhooks` library bundles a
fixed ±5-minute timestamp check inside its `verify(payload, headers)` and exposes no way to tune or
disable it. To make the window configurable — and enabled by default (`5m`) with an opt-out switch — `HmacWebhookVerifier` does not call the
library's `verify`; it recomputes the HMAC via `Webhook.sign` and performs the freshness check through a
one-method SPI:

```java
public interface TimestampValidator {
    /** Throws WebhookVerificationException if the (epoch-seconds) timestamp is outside the window. */
    void validate(long timestampSeconds);
}
```

Two implementations ship, wired `@ConditionalOnMissingBean` from configuration:

| Implementation | When | Behaviour |
| --- | --- | --- |
| `ClockSkewTimestampValidator` | **Default** (`replay-window-enabled=true`) | Accepts only if `\|now − timestamp\| ≤ replay-tolerance` (default `5m`); otherwise throws `"Message timestamp too old"` / `"Message timestamp too new"`. |
| `NoOpTimestampValidator` | `replay-window-enabled=false` | Accepts any timestamp. The signature alone is trusted. |

```yaml
webhooks:
  consumer:
    replay-window-enabled: true   # default: true
    replay-tolerance: 5m          # allowed clock skew either direction (default: 5m)
```

Publish your own `@Bean TimestampValidator` for a different freshness policy; the auto-configuration
backs off.

> **Security note — enabled by default.** Per the Standard Webhooks specification, the out-of-the-box
> consumer enforces `replay-tolerance` (`5m`) so captured deliveries cannot be replayed indefinitely.
> Pair it with idempotency (§11) or idempotent handlers for defense in depth. The boundary is inclusive
> (`≤`), the check runs before any signature work, and an unparseable timestamp is always rejected with
> `WebhookVerificationException("Invalid timestamp header")` regardless of the window setting.

### 8e. Asymmetric verification — Ed25519 `v2a` (`whpk_`)

Standard Webhooks defines an **asymmetric** signing scheme, `v2a`, alongside the symmetric `v1` HMAC.
The sender signs with an Ed25519 **private** key (`whsk_…`, §19i) and the receiver verifies with the
matching Ed25519 **public** key (`whpk_…`). The private key never leaves the sender, so a receiver — or
anyone who compromises the receiver's configuration — can verify deliveries but can never *forge* them.
This is the property a shared HMAC secret cannot give you: with `v1`, the secret that verifies is the
same secret that signs.

**Scheme selection is automatic, per secret, by prefix.** Configure an endpoint's secret as a `whpk_`
public key and `DefaultWebhookVerifier` routes it to `Ed25519WebhookVerifier`; configure a `whsec_`
secret and it stays on `v1`. Both may appear in the same `secret` + `additional-secrets` list during a
migration, and a delivery is accepted if any of them verifies (§8a). Nothing else changes: the same
three headers, the same replay window (§8b), the same trust-list rotation.

**The signed content includes the `endpoint-id`.** Where `v1` signs `msg_id.timestamp.payload`, `v2a`
signs `endpoint_id.msg_id.timestamp.payload`, then Ed25519-signs the SHA-256 of that string:

```
signature = base64( ed25519_sign( privateKey, SHA-256( endpoint_id . msg_id . timestamp . payload ) ) )
header:     webhook-signature: v2a,<base64 signature>      # space-separated alongside any v1,<…>
```

**Why the `endpoint-id` is required.** Under `v1` each endpoint has its own HMAC secret, so a signature is
implicitly bound to one endpoint — no one else holds the secret. Under `v2a` the verifying material is a
**public** key that is not secret and is commonly **reused across many endpoints/customers**, so a bare
signature over `msg_id.timestamp.payload` would verify at *any* endpoint trusting that key. That would let a
genuine delivery meant for endpoint A be captured and **cross-posted to endpoint B** (same key, so the
signature still checks). Prepending the `endpoint-id` binds each signature to exactly one endpoint — A's
signature is over `A.msg_id.timestamp.payload` and fails at B, which rebuilds `B.msg_id.timestamp.payload`.
It is the asymmetric counterpart of `v1`'s per-endpoint secret, and is orthogonal to the replay window
(§8b): the id blocks replay *to a different endpoint*, the window blocks replay *to the same endpoint later*.

The `endpoint-id` lets a single key pair serve many endpoints while preventing a signature made for one
endpoint from being replayed against another that trusts the same public key. It is **never transmitted**
— the verifier rebuilds the signed content using the endpoint's *own configured* `endpoint-id`, never a
value read from the request — so both sides must be configured with the **same** `endpoint-id` out of
band (see §19i for the producer side and `guides/verification-and-security.md` for a step-by-step setup).

```yaml
webhooks:
  consumer:
    endpoints:
      - path: /webhooks/crm
        endpoint-id: ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb   # required for v2a; must not contain '.'
        secret: ${CRM_WEBHOOK_PUBLIC_KEY}              # whpk_… the producer shared with you
        additional-secrets:                            # a second whpk_ (rotation) or a whsec_ (migration)
          - ${CRM_WEBHOOK_PUBLIC_KEY_PREVIOUS}
```

**Startup validation (§14a).** `SecretValidator` fails the context fast and loud if a `v2a` endpoint is
misconfigured: a missing `endpoint-id` when a `whpk_` key is present, an `endpoint-id` containing the
`.` delimiter, a malformed public key, or a `whsk_` **private** key configured where only the public key
belongs (a consumer must never hold the private key). Each failure is rendered by a Spring Boot
`FailureAnalyzer` as a clean `APPLICATION FAILED TO START` report naming the endpoint and the fix, never
the key material. An `endpoint-id` set on a purely `v1` endpoint is accepted but logs a warning that it
will be ignored.

> **Interoperability.** `v2a` is implemented with JDK-native Ed25519 (Java 15+, no extra dependency) and
> is verified byte-for-byte against the published Standard Webhooks worked example, so it interoperates
> with any conforming sender or SDK. The deprecated `v1a` variant is intentionally **not** implemented.

---

### 8f. Disabling message security

Signature verification is a **pluggable SPI with a disable switch**, mirroring the `TimestampValidator`
idiom. `WebhookVerifier` is an interface; two implementations ship, wired `@ConditionalOnMissingBean`
from configuration:

| Implementation | When | Behaviour |
| --- | --- | --- |
| `DefaultWebhookVerifier` | **Default** (`signature-verification-enabled=true`) | Verifies the `webhook-signature` against the endpoint's secret(s), selecting `v1` (HMAC) or `v2a` (Ed25519) per secret by prefix (§8e), rotation-aware, delegating freshness to the `TimestampValidator`. |
| `NoOpWebhookVerifier` | `signature-verification-enabled=false` | Accepts **every** request unconditionally; reads no secret, body, or header; never throws. |

```yaml
webhooks:
  consumer:
    signature-verification-enabled: false   # default: true
```

When disabled: no endpoint secret is required, `validate-secrets-on-startup` (§14a) is skipped, and the
auto-configuration logs a prominent `SECURITY: … DISABLED …` **WARN at every startup** so the state is
never silent. Override the bean with your own `@Bean WebhookVerifier` for a different *scheme* (rather
than disabling); the producer counterpart is §19h.

> **Security note.** With verification off the endpoint has **no message-authentication layer** and will
> accept forged payloads from anyone who can reach it. Only disable when authenticity is guaranteed by the
> transport instead (mTLS / an authenticating reverse proxy / a Spring Security resource-server chain in
> front of the endpoint). Message signing and transport auth (§19g) are complementary; prefer keeping both.

---

## 9. Webhook metadata object

Handlers often need more than the deserialized payload: they need the **event type**, both
timestamps, the `webhook-id` (for correlation/dedupe), and sometimes the raw headers.
We pass all of this in a single immutable `WebhookMetadata` object, built per request and handed to
the handler alongside the typed event.

The toolkit does **not** carry a trace/correlation id here, and never writes to the SLF4J `MDC`:
correlation and log context are the application's concern (§9b). The stable `webhook-id` — which *is* a
Standard Webhooks concept — is always available via `webhookId()`.

```java
public record WebhookMetadata(

    // --- Standard Webhooks envelope / headers ---
    String type,                     // envelope "type", e.g. "contact.created"
    String webhookId,                // webhook-id header (correlation + idempotency key)
    Instant attemptTimestamp,        // webhook-timestamp header (delivery attempt time)
    Instant eventTimestamp,          // body "timestamp" (when the event occurred) — nullable

    // --- Endpoint context ---
    String endpointPath,             // which configured path received it (e.g. /webhooks/crm)
    String sourceIp,                 // remote address of the sender

    // --- The deserialized event (log it via redactedEvent(), not toString()) ---
    Object event,                    // the typed event object; redactedEvent() masks @Redact fields (§9a)

    // --- Escape hatch: everything else ---
    Map<String, String> headers,     // all request headers (lower-cased), signature REDACTED
    Map<String, Object> attributes   // free-form extras (e.g. custom x-* headers), see below
) {

    /** Convenience: lag between now and the delivery attempt (see STANDARD_WEBHOOKS.md §3a). */
    public Optional<Duration> deliveryLag() {
        return Optional.ofNullable(attemptTimestamp).map(t -> Duration.between(t, Instant.now()));
    }

    /** Convenience: lag between now and the actual event time. Null-safe. */
    public Optional<Duration> eventLag() {
        return Optional.ofNullable(eventTimestamp).map(t -> Duration.between(t, Instant.now()));
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }
}
```

### How it is populated

Built in the controller right after verification succeeds:

| Field | Source |
| --- | --- |
| `type` | parsed from the envelope `type` |
| `webhookId` | `webhook-id` header |
| `attemptTimestamp` | `webhook-timestamp` header (unix seconds → `Instant`) |
| `eventTimestamp` | body `timestamp` (ISO 8601 → `Instant`), nullable |
| `endpointPath` | the matched `Endpoint.path` (from the argument resolver) |
| `sourceIp` | `request.getRemoteAddr()` (or `X-Forwarded-For` if behind a proxy) |
| `event` | the deserialized event object; for logging use `redactedEvent()`, which masks `@Redact` fields (§9a) |
| `headers` | all request headers, lower-cased, **with `webhook-signature` redacted** (`STANDARD_WEBHOOKS.md` §11) |
| `attributes` | optional extras; e.g. selected custom `x-*` headers the sender adds, or values a `MetadataContributor` SPI chooses to attach |

### Extensibility — `attributes` and contributors

The `attributes` map is the escape hatch for provider-specific metadata that isn't part of Standard
Webhooks (e.g. a vendor's `x-event-source`, `x-delivery-id`). An optional SPI lets apps enrich the
metadata without changing the core:

```java
public interface MetadataContributor {
    /** Add provider-specific fields to the attributes map. Runs after verification. */
    void contribute(HttpServletRequest request, Map<String, String> headers,
                    Map<String, Object> attributes);
}
```

All registered `MetadataContributor` beans run before dispatch. Keep contributions cheap and never
pull in secrets.

> ⚠️ **Security:** the signature value and the signing secret are **never** placed in
> `WebhookMetadata` (headers map redacts `webhook-signature`). This keeps the object safe to log.

---

## 9a. Field-level redaction — `@Redact`

Event payloads routinely carry sensitive values (email, card tails, tokens). Logging an event with
`event.toString()` or interpolating its getters leaks those values. The consumer provides a
**field-level `@Redact` annotation** so sensitive fields are masked wherever the framework renders an
event for logs.

### Annotating fields

Put `@Redact` on any field of a `@WebhookType` event class:

```java
@WebhookType("contact.created")
public class ContactCreated {
    private String id;
    private String fullName;

    @Redact                       // -> ***REDACTED***
    private String email;

    @Redact(show = 4)             // keep the last 4 chars, mask the rest: ************1234
    private String cardNumber;
}
```

- `@Redact` (default, `show = 0`) → the whole value becomes `***REDACTED***`.
- `@Redact(show = N)` → keeps the last `N` characters visible and masks the rest with `*`
  (if the value is `≤ N` characters it is fully redacted, so a short value can't leak).

### Logging through the redactor

Redaction is a **logging** concern only — the real value is still on the event object and available
to handler logic. The framework will not stop you from logging `event.getEmail()` directly; **it is
your responsibility to log through the redaction-aware renderer** instead. Two equivalent ways:

```java
// 1) From the metadata (recommended in handlers):
@WebhookHandler
public void onCreated(ContactCreated event, WebhookMetadata metadata) {
    log.info("contact.created id={} event={}", event.getId(), metadata.redactedEvent());
    // -> contact.created id=c-1 event=ContactCreated{id=c-1, fullName=John, email=***REDACTED***}

    doBusinessLogic(event.getEmail()); // real value — redaction never affects behaviour
}

// 2) Directly, for any object:
log.info("event={}", WebhookRedactor.toRedactedString(event));
```

`WebhookMetadata.redactedEvent()` simply delegates to `WebhookRedactor.toRedactedString(event())`.

### Performance — reflection is cached, no per-call penalty

`WebhookRedactor` reflects over a class **exactly once** and caches a precompiled `FieldPlan[]`
(the accessible `Field` handles plus each field's mask policy) in a JVM-managed `ClassValue`.
Every subsequent render of that type does **zero** annotation lookups and **zero** field discovery —
it just iterates the cached array and reads already-`setAccessible` handles. There is no reflection
cost on the hot path beyond the first event of each type.

---

## 9b. Correlation & trace context (app-owned)

**The toolkit writes nothing to the SLF4J `MDC`.** Correlation ids, trace ids, and the shape of your
log context are the application's concern, not the toolkit's. The toolkit owns the *protocol* — signature
verification, replay rejection, idempotency, typed dispatch — and deliberately stays out of your
observability stack. This keeps a clean ownership boundary and avoids the toolkit fighting whatever
tracing bridge (Micrometer Tracing / OpenTelemetry) or logging convention you already run.

What the toolkit *does* give you is everything you need to correlate:

- **`WebhookMetadata.webhookId()`** — the stable `webhook-id` from the Standard Webhooks envelope. It is
  **constant across every retry of the same event** (unlike a random per-request id), so it is the right
  key to correlate the whole delivery history of one event. It is available to every handler.
- **MDC propagation across the async hop.** When `@WebhookHandler` methods run on a pool thread (§15,
  §18), the toolkit captures whatever the application put in the request thread's `MDC` and
  re-establishes it on the worker for the duration of the handler, restoring the worker's prior `MDC`
  afterwards. The toolkit puts nothing in that map itself; it only faithfully carries *your* context
  across the thread boundary so your log lines stay correlated.

### The end-to-end pattern

Correlation is a producer→consumer concern, and both sides simply **agree on a header name out of band**
(different producer/consumer pairs may use different names — `x-trace-id`, `traceparent`, `x-request-id`,
…). The pattern is symmetric with how outbound auth is applied (§19g): a producer shapes the outbound
request through a `WebhookRequestInterceptor`; a consumer reads the header in its own servlet filter (or
lets a tracing bridge do it) and binds it to the `MDC` under whatever key its log pattern uses.

**Producer** — copy the current trace id onto an agreed header via a `WebhookRequestInterceptor`
(the single outbound-request-shaping strategy; §19g):

```java
@Bean
WebhookRequestInterceptor traceHeaderInterceptor() {
    return (url, headers, body) -> {
        String traceId = MDC.get("traceId");          // put there by the app's own request filter / bridge
        if (traceId == null) {
            return headers;                            // nothing to add
        }
        Map<String, List<String>> out = new LinkedHashMap<>(headers); // new map; keep the signed webhook-* headers
        out.put("x-trace-id", List.of(traceId));      // the header both sides agreed on
        return out;
    };
}
```

**Consumer** — read the agreed header into the `MDC` in a servlet filter that runs *before* the webhook
controller, and clear it in a `finally` so it never leaks onto a pooled thread:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader("x-trace-id");  // the header both sides agreed on
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();     // mint one so every delivery is correlatable
        }
        MDC.put("traceId", traceId);
        try {
            chain.doFilter(req, res);                    // webhook controller + handler run with MDC set
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

With `traceId` in the `MDC`, add it to your log pattern (`%X{traceId}`) and it appears on every line —
including the handler's, because the toolkit propagates the `MDC` across the async hop. A full, runnable
version of this producer→consumer example lives in the sample apps.

> **Trace headers are untrusted.** They are **not** covered by the webhook signature. Use them only to
> link traces/logs — never for authorization or any security decision (`STANDARD_WEBHOOKS.md` §11).

---

## 10. Typed handler dispatch

Handler annotation (like `@EventListener`):

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface WebhookHandler { }
```

A handler method takes the typed event and, **optionally**, the `WebhookMetadata`. Both of these are
valid and the dispatcher picks the right invocation by parameter signature:

```java
@Component
public class ContactHandlers {

    // Event only
    @WebhookHandler
    public void on(ContactCreated event) {
        // business logic
    }

    // Event + metadata  (type, timestamps, webhook-id, headers, ...)
    @WebhookHandler
    public void on(ContactUpdated event, WebhookMetadata metadata) {
        log.info("handling {} webhookId={} eventLag={}",
                 metadata.type(), metadata.webhookId(),
                 metadata.eventLag().orElse(null));
        // business logic
    }
}
```

Supported handler signatures (dispatcher resolves by parameter types; order-independent):

- `(EventType event)`
- `(EventType event, WebhookMetadata metadata)`
- `(WebhookMetadata metadata, EventType event)`

The **event parameter's type** is the routing key — it must be a `@WebhookType`-annotated class. The
`WebhookMetadata` parameter is recognised by its type, not position.

`WebhookDispatcher` scans `@WebhookHandler` methods at startup, validates each has exactly one
`@WebhookType` parameter (plus an optional `WebhookMetadata`), and builds a `Map<Class<?>, HandlerMethod>`
keyed on the event type. At dispatch it invokes the method, supplying the event and metadata as the
method declares.

```java
public void dispatch(Object event, WebhookMetadata metadata) {
    HandlerMethod h = handlers.get(event.getClass());
    if (h == null) {
        // A known @WebhookType reached dispatch with no @WebhookHandler — a config gap, not a sender
        // error. Throw (→ 500) rather than silently acking, so the sender keeps retrying while it's
        // fixed and the gap is loud (§12). Compare §6: a type the toolkit does NOT model is ack-ignored.
        throw new NoWebhookHandlerException(metadata.type(), event.getClass().getName());
    }
    h.invoke(event, metadata);   // passes metadata only if the method declares it
}
```

`dispatch(...)` returns `void` and signals a missing handler by throwing `NoWebhookHandlerException`
(→ `500`, `urn:webhooks:no-handler`, §12). This keeps "we don't model this type" (ack-and-ignore, §6)
cleanly distinct from "we model it but forgot to wire a handler" (a server-side configuration fault).

### Generic controller tying it together

```java
@Component
@RequiredArgsConstructor
public class WebhookReceiveController {

    private final WebhookVerifier verifier;
    private final WebhookTypeRegistry typeRegistry;
    private final WebhookDispatcher dispatcher;
    private final MetadataFactory metadataFactory;   // builds WebhookMetadata (§9)
    private final IdempotencyGuard idempotency;      // optional, §11
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public ResponseEntity<Void> handle(HttpServletRequest request,
                                       @RequestBody(required = false) String rawBody,
                                       Map<String, List<String>> headers) {   // secret injected via resolver

        String secret = (String) request.getAttribute("webhook.secret");

        verifier.verify(secret, rawBody, headers);          // 1) verify RAW body (throws on failure)

        String webhookId = first(headers, "webhook-id");
        if (!idempotency.tryBegin(webhookId)) {              // 2) optional best-effort reserve
            return ResponseEntity.noContent().build();       //    duplicate: already reserved/committed
        }
        try {
            Envelope env = objectMapper.readValue(rawBody, Envelope.class);
            Class<?> clazz = typeRegistry.classOf(env.getType());
            if (clazz == null) {
                return unknownType(env.getType());           // 3) policy: ack-and-ignore | reject
            }

            Object event = objectMapper.convertValue(env.getData(), clazz);
            WebhookMetadata metadata = metadataFactory.build(request, env, headers);  // 4) build metadata
            dispatcher.dispatch(event, metadata);            // 5) @WebhookHandler; no handler => throws (500)
            idempotency.markProcessed(webhookId);            // 6) commit the reservation on success
            return ResponseEntity.noContent().build();       // 204 => success
        } catch (RuntimeException ex) {
            idempotency.release(webhookId);                  // drop the reservation so a retry can proceed
            throw ex;
        }
    }
}
```

---

## 11. Idempotency without storage

Real idempotency ("process each `webhook-id` exactly once, ever") requires a store, which we're not
building. Options given the no-storage constraint:

1. **None (recommended default).** Make handlers **naturally idempotent** (e.g. upserts in the
   downstream system), paired with the enabled-by-default replay window (§8b, `5m` tolerance) for replay security.
   This is the cleanest fit for "no storage."
2. **In-memory best-effort** `IdempotencyGuard` — a bounded, time-expiring record of recent
   `webhook-id`s (e.g. Caffeine with 5-min TTL). Catches duplicate retries within one instance's
   lifetime; does **not** survive restarts or work across instances. Off by default; enabled with
   `webhooks.consumer.in-memory-idempotency: true`.

**The SPI is a reserve/commit protocol, not a bare `seen()` check.** A naïve "have I seen this id?"
marks an id as processed *before* the handler runs, so a handler that then fails is never retried — the
delivery is silently lost. Instead the guard exposes three operations the pipeline drives around
dispatch:

```java
public interface IdempotencyGuard {

    /** Reserve this id. true => proceed (first sight); false => duplicate, skip (already reserved/committed). */
    boolean tryBegin(String webhookId);

    /** Commit a reservation after the handler succeeds, so later retries of this id are suppressed. */
    void markProcessed(String webhookId);

    /** Drop a reservation after the handler fails, so the sender's retry can be processed afresh. */
    void release(String webhookId);
}
```

The pipeline calls `tryBegin` after verification; on a `false` it returns `204` without dispatching. It
`markProcessed` only **after** `dispatch(...)` returns normally, and `release`s on any `RuntimeException`
so a failed delivery is retryable rather than swallowed. The no-op default (`tryBegin` always `true`,
`markProcessed`/`release` no-ops) never dedupes; the in-memory implementation remembers committed ids for
its TTL window.

> If strong, cross-instance idempotency is ever required, that's the one place we'd need external
> state (Redis). Out of scope here; flagged as an open question (§14). A Redis-backed `IdempotencyGuard`
> implements the same reserve/commit SPI (e.g. `SET NX` for `tryBegin`).

---

## 12. Error handling & status codes

Reuse `ProblemDetail` + `@ControllerAdvice` from `dynamic-rest-api`'s `GlobalExceptionHandler`. The
status code is the **only** thing the sender reacts to (it drives *their* retries — `STANDARD_WEBHOOKS.md` §8).

| Situation | HTTP | URN (`type`) | Why |
| --- | --- | --- | --- |
| Verified + handled OK | `204` | — | 2xx → sender marks delivery successful. |
| Unknown `type`, policy `ack-and-ignore` | `204` | — | We don't model it; tell the sender to stop retrying. |
| `WebhookVerificationException` | `400` | `urn:webhooks:verification-failed` | Bad signature / stale-or-future timestamp / missing headers. Log WARN. |
| Payload validation failed (Bean Validation, §6a) | `400` | `urn:webhooks:payload-validation-failed` | RFC-7807 body with `eventType` + `errors[]`. |
| **Malformed payload** (`InvalidPayloadException`) | `400` | `urn:webhooks:invalid-payload` | Verified body could not be parsed as an envelope, or its `data` failed strict deserialization (unknown JSON fields, type mismatch). Carries `eventType` when known. |
| Unknown `type`, policy `reject` | `400` | `urn:webhooks:unknown-type` | Carries `eventType` + `path`. |
| Body exceeded `max-body-size` | `413` | `urn:webhooks:payload-too-large` | Rejected before verification; carries `limitBytes`. |
| **No `@WebhookHandler` for a modelled, accepted type** (`NoWebhookHandlerException`) | `500` | `urn:webhooks:no-handler` | A known `@WebhookType` reached dispatch with no registered handler — a configuration gap. `500` so the sender retries while it's fixed, rather than silently dropping (SPEC §10). Carries `eventType`. |
| Endpoint path not configured | `500` | `urn:webhooks:endpoint-not-configured` | Routing/config problem, not a sender error. |
| Handler threw | `500` | `urn:webhooks:handling-failed` | Non-2xx → sender retries; keep handlers idempotent (§10). |

> **Malformed vs. invalid.** A body that is syntactically broken (bad JSON envelope) and a body whose
> typed `data` is rejected by the strict reader both surface as `InvalidPayloadException` → `400`
> (`urn:webhooks:invalid-payload`). The two constructors differ only in whether the event `type` was
> already known (the type-deserialize path carries it; the envelope-parse path does not).

> **No handler is a server fault, not a client fault.** When a known type reaches dispatch with no
> `@WebhookHandler`, the pipeline throws `NoWebhookHandlerException` → `500` rather than acking. A `2xx`
> would tell the sender "processed" and let it stop retrying, silently discarding every delivery of that
> type until someone notices. A `500` keeps the sender retrying (its normal backoff) and makes the gap
> loud. Returning "accept-and-ignore" is reserved for types the toolkit does **not** model (§6).

---

## 13. Observability

> **Operator-facing summary, the available metrics, and the on/off knobs are in the README
> ([Configuration reference → Observability](../../README.md#observability)).**
> This section is the design rationale behind those knobs.

Follow `STANDARD_WEBHOOKS.md` §11 (consumer-side guidance):

- **Correlate on `webhook-id`** across verify → deserialize → dispatch. The stable `webhook-id` is
  always on `WebhookMetadata.webhookId()`; the toolkit itself writes nothing to the SLF4J `MDC`, so the
  application owns how (and under which key) it binds correlation to its log context (§9b).
- **Never log** the secret or raw `webhook-signature`; avoid logging full payload bodies by default.

With no storage there is no "failed deliveries" screen on our side — observability (logs, traces,
metrics) is the entire failure-visibility story for both the consumer and the producer. It is therefore
**on by default** and built entirely from the **standard Spring stack**.

### 13.1 What is instrumented

Both libraries instrument their hot path through the Micrometer **Observation API** via the declarative
`@Observed` annotation — not hand-rolled counters. One observation wraps each business operation, so a
single instrument yields **both a metric (timer) and a tracing span**, with the backend chosen by the
host app's normal Boot auto-configuration:

| Observation | `@Observed` site | Metric | Span |
| --- | --- | --- | --- |
| `webhook.receive` | `WebhookDispatcher#dispatch` (consumer) | `webhook.receive` timer | `webhook receive` |
| `webhook.send` | `WebhookPublisher#publish` (producer) | `webhook.send` timer | `webhook send` |

Each metric is a standard Micrometer **timer**, so it exposes count/sum/max (e.g. under Prometheus:
`webhook_receive_seconds_count` / `_sum` / `_max`, and the matching `webhook_send_seconds_*`) plus the
observation's automatic `error` tag. The `@Observed` sites add **no custom `KeyValue`s** — there is
deliberately no `type` or `destination` dimension yet; adding one would mean attaching an
`ObservationConvention` (overridable) rather than hand-tagging, to keep cardinality controlled. The
inbound HTTP layer is measured separately by actuator's `http.server.requests` (§13.2).

### 13.2 How it is wired (stock components only)

- **`@Observed` + `ObservedAspect`.** Each library contributes a Micrometer `ObservedAspect`
  (`@ConditionalOnClass` AspectJ, `@ConditionalOnMissingBean`) so the annotated, AOP-proxied bean
  method is advised. Both annotated methods (`dispatch`, `publish`) are ordinary proxied Spring beans;
  the reflectively-registered `WebhookReceiveController#handle` is **not** proxied, so instrumentation
  deliberately lives on the dispatcher, not the controller. Actuator's own `http.server.requests`
  already covers the HTTP layer of the receive endpoint. **AOP is an optional dependency**
  (`spring-boot-starter-aop`, `<optional>true</optional>`): the `ObservedAspect` bean — and therefore the
  `webhook.receive` / `webhook.send` timers and spans — materialises only when the host app has AspectJ on
  the classpath. A `MeterRegistry` alone is not enough; without AOP the libraries run fully but emit no
  `@Observed` timing for these two methods.
- **`ObservationRegistry` from the host.** When the host app has `spring-boot-starter-actuator`, Boot
  auto-wires a real `ObservationRegistry` bound to its `MeterRegistry` (metrics) and, if a tracer
  bridge (OTel/Brave) is present, to tracing (spans). That registry is used automatically.
- **Inject-safe default.** If nothing provides one, each auto-config supplies
  `ObservationRegistry.NOOP` (`@ConditionalOnMissingBean`), so `@Observed` is always satisfiable and
  the hot path stays branch-free — no metrics/spans, no failure.

### 13.3 Turning it on and off

There is **no custom flag** — control it exactly as any other Spring observation, via the stock
`management.observations.enable.*` properties. This is the same table surfaced in the README:

| Scope | Property | Effect |
| --- | --- | --- |
| Consumer receive | `management.observations.enable.webhook.receive=false` | No `webhook.receive` metric/span |
| Producer send | `management.observations.enable.webhook.send=false` | No `webhook.send` metric/span |
| Everything | `management.observations.enable=false` | Disables all observations in the app |

No `webhooks.*.observations.*` flag is introduced. Where the data lands (metrics, spans, both, or
nothing) is entirely a function of what the host app wires — see the README subsection linked above.

---

## 14. Module / package layout

Spring Boot 3.3 / Java 21. A **five-module Maven reactor** of libraries. A shared `webhooks-core` holds the
config-free wire model and SPIs; each side ships a **starter + autoconfigure pair** —
`webhooks-consumer-autoconfigure` / `webhooks-producer-autoconfigure` carry the auto-configured code (each
depends on core), and the thin `webhooks-consumer-starter` / `webhooks-producer-starter` poms are what a
downstream app depends on (they transitively bring the matching autoconfigure module plus its runtime
deps — the canonical Spring Boot starter pattern). Each library is wired purely through
`AutoConfiguration.imports` (no component-scan of the library). The runnable demos are two **standalone
apps** under `webhooks-sample/` that depend only on the published starters and are **not** part of this reactor.

```
webhooks/                              # parent POM (pom packaging), shared dependencyManagement
├── webhooks-core/                     # SHARED (plain jar) — package com.jinternals.webhooks.core
│   ├── annotations/      @WebhookType, @Redact                 (shared by both sides)
│   ├── model/            Envelope, WebhookHeaders        (the Standard Webhooks wire format)
│   ├── redact/           WebhookRedactor                 (log-safe rendering, cached reflection §9a)
│   ├── registry/         WebhookTypeRegistry             (immutable type → class map holder; no scanning)
│   └── secret/           SecretProvider<T>               (generic secret-resolution SPI, §8a / §19)
│
├── webhooks-consumer-autoconfigure/   # CONSUMER AUTOCONFIGURE (auto-configured) — package …webhooks.consumer
│   ├── annotations/      @WebhookHandler                 (consumer-only)
│   ├── config/           WebhookProperties, WebhookConsumerAutoConfiguration (core beans),
│   │                     WebhookConsumerWebAutoConfiguration (web-only)
│   ├── registry/         WebhookTypeScanner              (Spring classpath scan: type → class)
│   ├── web/              WebhookEndpointRegistry, WebhookReceiveController, WebhookReceivePipeline,
│   │                     RawBodyArgumentResolver, EndpointResolvingInterceptor, WebConfiguration
│   ├── web/receive/      WebhookBodyVerifier, EventResolver, Resolution, WebhookRequestHeaders,
│   │                     ReceiveObservabilityRecorder, TimedHandlerDispatcher  (SRP stages, §11)
│   ├── verify/           WebhookVerifier (wraps standardwebhooks),
│   │                     ConfigSecretProvider implements SecretProvider<Endpoint>, SecretValidator
│   ├── validation/       PayloadValidator (Bean Validation on the @WebhookType object, §6a)
│   ├── metadata/         MetadataFactory, MetadataContributor (SPI); model/ WebhookMetadata
│   ├── dispatch/         WebhookDispatcher (@Observed "webhook.receive", §13), WebhookHandlerMethod,
│   │                     HandlerExecutor (SPI) + Sync/Async impls (§15)
│   ├── idempotency/      IdempotencyGuard (reserve/commit SPI, §11; no-op default, optional in-memory)
│   ├── web/error/        GlobalExceptionHandler (ProblemDetail), UnknownTypeException,
│   │                     InvalidPayloadException (→400), NoWebhookHandlerException (→500), …
│   └── resources/META-INF/spring/…AutoConfiguration.imports
│
├── webhooks-consumer-starter/         # CONSUMER STARTER (thin pom) — depends on …-autoconfigure + runtime deps
│
├── webhooks-producer-autoconfigure/   # PRODUCER AUTOCONFIGURE (auto-configured) — package …webhooks.producer  (§19)
│   ├── annotations/      @WebhookClient, @EnableWebhookClients
│   ├── config/           WebhookProducerProperties (named destinations), WebhookProducerAutoConfiguration
│   ├── secret/           ConfigDestinationSecretProvider implements SecretProvider<Destination>
│   ├── sign/             WebhookSigner (wraps standardwebhooks sign)
│   ├── transport/        WebhookTransport (SPI) + RestClientWebhookTransport, WebhookDeliveryException
│   ├── client/           WebhookPublisher (@Observed "webhook.send" build+sign+send engine, §13), WebhookClientInvocationHandler
│   │                     client/registrar/ WebhookClientRegistrar, WebhookClientFactoryBean (wiring glue)
│   └── resources/META-INF/spring/…AutoConfiguration.imports
│
└── webhooks-producer-starter/         # PRODUCER STARTER (thin pom) — depends on …-autoconfigure + runtime deps

webhooks-sample/                                # STANDALONE DEMO APPS (NOT part of the reactor above)
├── producer-sample/                   # sends webhooks (port 9090) — depends on webhooks-producer-starter
│   ├── Application.java               # @SpringBootApplication + @EnableWebhookClients(CrmWebhooks, CrmWebhooksV2a)
│   ├── events/                        @WebhookType events (ContactCreated)
│   ├── client/           CrmWebhooks (v1), CrmWebhooksV2a (v2a)  @WebhookClient     (producer demo)
│   ├── trace/            TraceHeaderWebhookInterceptor  copies MDC traceId → x-trace-id
│   └── web/              PublishController  POST /publish/contact-created[-v2a]      (producer demo)
├── consumer-sample/                   # receives webhooks (port 9091) — depends on webhooks-consumer-starter
│   ├── Application.java               # @SpringBootApplication
│   ├── events/                        @WebhookType events
│   ├── handlers/                      @WebhookHandler beans                         (consumer demo)
│   ├── trace/            TraceContextFilter  binds webhook-id + x-trace-id to the MDC
│   └── SampleMetadataContributor      demo MetadataContributor
├── docker-compose.yml                 # brings both apps up together
└── integration-test.sh               # cross-library proof: publish via producer → assert consumer handled it
```

**Core dependencies:** `jackson-databind` (the `Envelope.data` node), `spring-core`
(`ReflectionUtils`). Core deliberately has **no classpath-scanning code** — scanning
of `@WebhookType` classes is a consumer concern (`WebhookTypeScanner`), so the shared artifact stays
lean and a producer never carries the scanner.

**Consumer dependencies:** `webhooks-core`, `spring-boot-starter-web`, `spring-boot-autoconfigure`
(the `@WebhookType` scan uses Spring's `ClassPathScanningCandidateComponentProvider`, via `WebhookTypeScanner`),
`io.micrometer:micrometer-observation` + `spring-boot-starter-aop` (the `@Observed` Observation API,
§13), `com.standardwebhooks:standardwebhooks`, `spring-boot-starter-validation` (Hibernate Validator,
§6a), Jackson (via Boot).

**Producer dependencies:** `webhooks-core`, `org.springframework:spring-web` (the `RestClient` HTTP
client only — the producer is a webhook **client** and never serves HTTP, so it deliberately does **not**
depend on `spring-boot-starter-web` / the servlet stack), `spring-boot-autoconfigure`,
`io.micrometer:micrometer-observation` + `spring-boot-starter-aop` (the `@Observed` Observation API,
§13), `com.standardwebhooks:standardwebhooks`, Jackson (via Boot).

**Sample-app dependencies:** `producer-sample` depends on `webhooks-producer-starter` and `consumer-sample`
on `webhooks-consumer-starter` (each starter transitively brings its autoconfigure module and
`webhooks-core`), plus `spring-boot-starter-actuator` + `micrometer-registry-prometheus` to auto-wire the
`ObservationRegistry` and expose the `webhook.receive`/`webhook.send` observations (§13). The sample apps
link only against **published artifacts** — no source is shared with the libraries. The producer's `demo`
destination points at the consumer app's `/webhooks/crm` endpoint, so `POST /publish/contact-created`
publishes a real signed webhook the consumer side then receives — a full cross-library round-trip (brought
up together and asserted by `webhooks-sample/integration-test.sh`).

> **Published POMs are self-contained.** The consumer and producer autoconfigure modules declare their
> `webhooks-core` dependency with an explicit `<version>` (not inherited from the reactor's
> `dependencyManagement`), so an app resolving the starters as published artifacts — as the sample apps do
> — still pulls `webhooks-core` (and the consumer's `spring-boot-starter-web`) transitively.

> **Shared core, no config leakage.** `webhooks-core` is config-free: the secret SPI is a generic
> `SecretProvider<T>` so each side binds its own target (`SecretProvider<Endpoint>` on the consumer,
> `SecretProvider<Destination>` on the producer) without either side's `@ConfigurationProperties` type
> reaching core.

> **Auto-configuration, not component scan.** Each library registers its auto-configurations through
> `AutoConfiguration.imports`; they are **not** found by the app's `@SpringBootApplication` scan. Every
> package tree (`…core`, `…consumer`, `…producer`, `…sample`) is a sibling, so they never overlap.

**Drop-in modularity (coexisting with an existing Spring Boot app).** The library is designed to be
added to an already-running application without disturbing it:

- **No `@ComponentScan`, explicit `@Bean`s only.** Neither auto-configuration component-scans the
  library. Every bean is a plain `@Bean` method guarded by `@ConditionalOnMissingBean`. Nothing is
  picked up by the host's scan, there are no eager scan side effects, and the host can override *any*
  piece — `WebhookVerifier`, `SecretProvider`, `IdempotencyGuard`, `HandlerExecutor`,
  `PayloadValidator`, the controller, even the error handler — simply by defining its own bean. The
  library classes carry **no stereotype annotations** (`@Component`/`@Configuration`/`@RestControllerAdvice`),
  so they are only ever wired through the auto-config.
- **Core / web split.** `WebhookConsumerAutoConfiguration` registers the core beans (registry,
  verifier, dispatcher, metadata, payload validator, idempotency, secret provider/validator, handler executor).
  `WebhookConsumerWebAutoConfiguration` registers the servlet-web beans (controller, endpoint
  registration, MVC customizations, metrics, error handler) and is gated with
  `@ConditionalOnWebApplication(SERVLET)` and ordered `after` `WebMvcAutoConfiguration`. Core beans
  load even in a non-web context; nothing drags MVC types in where they don't belong. The observability
  beans (`ObservationRegistry` fallback + `ObservedAspect`) live in the **core** auto-config, so
  `@Observed` works in any context.
- **Scoped MVC footprint.** The library contributes one `WebMvcConfigurer` to the host's shared MVC
  config. Its endpoint-resolving **interceptor is registered only for the configured webhook listen
  paths** (null/blank paths dropped), so it never runs on the host's own requests. Its raw-body
  argument resolver is inert unless a controller requests that exact parameter type.
- **Scoped error handling.** `GlobalExceptionHandler` is a `@RestControllerAdvice(assignableTypes =
  WebhookReceiveController.class)`, so its RFC-7807 mapping — including the catch-all
  `Exception` → `500` — applies **only** to the webhook controller and never intercepts the host
  app's own controllers.
- **Graceful observability fallback.** Instrumentation uses the standard `@Observed` + `ObservedAspect`
  (§13). When the host has actuator, its auto-wired `ObservationRegistry` (metrics via `MeterRegistry`,
  traces via a tracer bridge) is used automatically; otherwise each auto-config supplies
  `ObservationRegistry.NOOP` (`@ConditionalOnMissingBean`) so `@Observed` is always satisfiable and the
  hot path stays branch-free. Observations are toggled the standard way via
  `management.observations.enable.*` — no custom flag.

---

### 14a. Production hardening

The receive path is a public, internet-facing POST that an untrusted sender controls. Three
hardening measures close the gaps a naive controller would leave open.

**1. Body-size cap (`413` before verification).** An inbound body is read through
`RawBodyArgumentResolver`, which streams the request and aborts as soon as the byte count exceeds
`webhooks.consumer.max-body-size` (default **256&nbsp;KB**), throwing `PayloadTooLargeException`
→ `413 Payload Too Large`. The cap is enforced **before** HMAC verification and before any JSON
parsing, so an oversized or deliberately slow POST cannot exhaust memory or tie up a request thread
reading megabytes we would only reject afterwards.

```yaml
webhooks:
  consumer:
    max-body-size: 256KB      # Spring DataSize; raise per your largest legitimate payload
```

**2. Startup secret validation (fail fast, not per-request `500`).** `SecretValidator`
(`InitializingBean`) runs at boot and, for every configured endpoint, asserts the `SecretProvider`
returns at least one secret and that each secret is a well-formed Standard Webhooks secret
(constructing a `Webhook` would otherwise throw). A missing, blank, or malformed secret **fails the
application context at startup** — loud and immediate — instead of surfacing later as a per-request
`500` that a sender would retry forever. The real secret value is never logged. Disable with
`webhooks.consumer.validate-secrets-on-startup: false`.

**3. Endpoint null-guard (defensive `500`).** The generic `WebhookReceiveController` reads the
matched `Endpoint` from a request attribute set by `EndpointResolvingInterceptor`. If that attribute
is absent — which should be impossible once the interceptor is wired, but would mean a request
reached the controller for a path with no registered endpoint — the controller throws
`EndpointNotConfiguredException` → `500` rather than dereferencing null. This is a belt-and-braces
guard against a routing/wiring regression, not an expected runtime path.

**4. Configuration invariants validated at bind time (`@Validated`).** `WebhookProperties` is
`@Validated`, so a misconfiguration **fails the application context at startup** with a clear message
rather than surfacing later as a raw executor/runtime exception on the first request. Beyond the
individual `@Positive`/`@NotNull`/path constraints, two classes of invariant that a single-field
annotation cannot express are enforced:

- **Async pool sizing** — `core-pool-size` must not exceed `max-pool-size`. `ThreadPoolExecutor`
  only rejects that combination when the pool is first constructed (lazily, on the first async
  delivery), so without this check a bad value would pass startup and then fail the first webhook.
- **Positive async timeout** — `handler.timeout` must be strictly positive; a zero/negative value
  would schedule every handler's cancel immediately, interrupting each async handler the instant it
  starts.

The remaining caps are likewise checked: `max-body-size` and `in-memory-idempotency-max-size` must
be positive (a non-positive body cap would reject every non-empty body as "too large"), and
`replay-tolerance` must not be negative (a negative tolerance makes `|now − timestamp| ≤ tolerance`
unsatisfiable, rejecting every request when the replay window is enabled).

---

## 15. Sync vs async handling

Handler execution is a **swappable strategy** behind the `HandlerExecutor` SPI (package
`…consumer.dispatch`). The controller builds the handler invocation as a `Runnable` and hands it to
the configured executor; the executor decides whether to run it inline or off-thread. The default is
**sync**. Select the mode with `webhooks.consumer.handler.mode`, or override the `HandlerExecutor`
bean entirely (`@ConditionalOnMissingBean`).

```yaml
webhooks:
  consumer:
    handler:
      mode: sync            # sync (default) | async
      # async-only tuning:
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 1000  # full queue → CallerRunsPolicy back-pressure (runs on request thread)
      timeout: 30s          # per-handler; a handler exceeding it is interrupted and logged
```

**`sync` (default) — `SyncHandlerExecutor`.** Handlers run **inline** on the request thread, so the
`2xx` is returned only after the handler completes — a `2xx` means **"processed"**. This fits the
Standard Webhooks delivery model, where the sender treats a `2xx` as success and a timeout as a
failure to retry (see `STANDARD_WEBHOOKS.md`). A handler that throws turns into the right status code,
and the sender's own retry machinery is the back-pressure — no in-JVM queue to lose work on restart,
and no storage (which is out of scope).

**`async` — `AsyncHandlerExecutor`.** Handlers run **off the request thread** on a bounded pool and
the controller responds `204` immediately — a `2xx` means **"accepted"**, not "processed". Use this
when handlers are slow and the sender's timeout is tight. It is **bounded** (core/max threads +
work-queue capacity; a full queue applies `CallerRunsPolicy` back-pressure instead of growing memory)
and each task carries a **per-handler timeout**: a single scheduler arms a one-shot cancel of the
task after `handler.timeout`, interrupting a stuck handler so it cannot tie up a pool thread forever.
The trade-off is explicit: a crash before the queued task runs **drops that delivery**, so async mode
is for handlers that are best-effort or themselves idempotent and backed by the sender's retries.

> **No ordering in `async` mode.** Async handlers run **concurrently and in no guaranteed order**. Even
> for events that arrive in order A → B this mode can complete B before A, because: `max-pool-size`
> workers run in parallel on different threads; a full queue makes the *next* event run inline on the
> request thread (`CallerRunsPolicy`) while earlier queued events still wait; and a slow event can time
> out while a later fast one completes. If a handler must process a related series of events in order
> (e.g. `created` before `updated` for the same entity), **do not rely on async**: either use `sync`
> mode (each delivery is processed to completion before its `2xx`, though cross-request ordering is
> still the *sender's* concern, not the toolkit's — webhooks carry no cross-request ordering contract),
> or make the handler order-tolerant (reconcile against a version/timestamp on the event, or serialise
> per entity key in your own infrastructure). The toolkit holds no state and therefore cannot sequence
> events for you.

**The budget.** Senders typically allow a **15–30&nbsp;second** response window. In `sync` mode a
handler must stay comfortably inside it; slow work (calling third parties, heavy DB writes) should be
pushed either to `async` mode or to the application's own asynchronous infrastructure *inside* the
handler, after it has extracted what it needs from the event. See §18 for performance guidance.

---

## 16. Quality gate — coverage & mutation testing

Every module enforces a **100% coverage + 100% mutation** gate, bound to `verify`. The JaCoCo gate runs on
every `mvn verify`; the slower PITest gate is skipped locally unless the `mutation` profile is active
(`mvn verify -Pmutation`, `skipPitest=false`), and CI always runs it. Either fails the build if quality
regresses. Two complementary tools:

- **JaCoCo** — 100% **line *and* branch** coverage. Catches unexecuted code.
- **PITest (PIT)** — 100% **mutation** score (STRONGER mutators) *and* 100% PIT line coverage.
  Catches code that is executed but not actually *asserted on*: PIT mutates the bytecode (flips
  conditionals, changes return values, removes calls) and a test suite that stays green against a
  mutant has a blind spot. Mutation testing is the stronger signal; JaCoCo is the floor.

Both thresholds are literal `100`. A build that passes has no un-asserted logic in the gated scope.

**Honesty rules (no gaming the number).**

- Prefer a real test over an exclusion. Every mutant is either killed by a genuine assertion or the
  underlying code is removed because it was provably dead/equivalent.
- **Equivalent mutants** (mutations that cannot change observable behavior) are resolved by deleting
  the redundant code, not by lowering a threshold or faking a test. Example: `MetadataFactory`'s
  `|| value.isBlank()` was redundant with the surrounding `try/catch` (a blank string throws on
  parse and is caught to the same `null`), so it was removed.
- **Documented exclusions** are a last resort. The *only* JaCoCo/PIT exclusion in either
  auto-configure module is the `annotations/**` package — annotation types carry no executable code
  and nothing to mutate. Everything else, including the `config/**` auto-configuration wiring, the
  `@Data` `@ConfigurationProperties`, the startup validators, and the `@WebhookClient`
  registrar/factory-bean, is covered to 100% JaCoCo line+branch **and** mutated to a 100% PIT score —
  there are no per-class PIT wiring exclusions. The enforced thresholds and the sole `annotations/**`
  exclusion live in each module's `pom.xml` (JaCoCo + PITest, bound to `verify`).
- **Mutation-inert code is acceptable and documented.** Where a method only calls `log.info`, Gregor
  generates no mutations for the pure logging method — such code is JaCoCo-covered but there is simply
  nothing to mutate, and that is recorded rather than hidden.

Current status: the full library reactor `mvn clean verify -Pmutation` is **green** — every gated module at 100%
mutation across its gated scope and 100% JaCoCo line+branch (`webhooks-core`,
`webhooks-consumer-autoconfigure`, `webhooks-producer-autoconfigure`; the thin
`webhooks-consumer-starter` / `webhooks-producer-starter` poms carry no gated logic). The standalone
sample apps under `webhooks-sample/` are outside the gated reactor. The auto-configuration wiring under
`config/**` is **not** excluded — it is covered to 100% JaCoCo line+branch and mutated to a 100% PIT
score like the rest of the module; the sole exclusion is the executable-code-free `annotations/**`
package. The live counts come from `mvn clean verify -Pmutation` itself (JaCoCo + PITest reports under each
module's `target/`).

**Dependency vulnerability scanning.** Beyond coverage/mutation, the build can scan dependencies for
known CVEs with [OWASP dependency-check](https://owasp.org/www-project-dependency-check/), run via the
opt-in `security-scan` profile (`mvn -Psecurity-scan verify`) so the fast gate stays offline. It fails on
a CVSS ≥ 7 finding. See §19e.

---

## 17. Open questions

1. ~~**Single app vs library+starter**~~ — *Resolved:* shipped as **starter + autoconfigure pairs**
   (`webhooks-consumer-starter` + `webhooks-consumer-autoconfigure`, `webhooks-producer-starter` +
   `webhooks-producer-autoconfigure`, sharing `webhooks-core`) in a five-module library reactor, with the
   demos as separate standalone apps under `webhooks-sample/` (§14).
2. **Idempotency** — is the no-storage default (rely on replay window + idempotent handlers)
   acceptable, or do we need the optional in-memory guard enabled, or eventually Redis?
3. ~~**Schema validation**~~ — *Resolved:* implemented as **per-type payload validation** via Bean
   Validation on the `@WebhookType` class (§6a). The type *is* the schema — constraints live on the event
   class, so there is no per-type schema config. Every deserialized event is validated.
4. ~~**Sync vs async handling**~~ — *Resolved:* **both are shipped** behind the `HandlerExecutor`
   SPI (§15). The default `sync` runs `@WebhookHandler` **inline** and returns the `2xx` after the
   handler completes (a `2xx` means "processed"). Opt into `async` (`webhooks.consumer.handler.mode:
   async`) for a bounded off-thread pool with a per-handler timeout that returns `204` immediately
   (a `2xx` means "accepted"); the delivery-contract trade-off is documented in §15.
5. ~~**Secret source**~~ — *Resolved:* a `SecretProvider` SPI (§8a) with a default
   `ConfigSecretProvider` that reads `application.yaml` / env vars. Returning multiple secrets enables
   zero-downtime rotation; override the bean to source from a secrets manager.
6. ~~**Pluggable message-security model**~~ — *Resolved:* the *transport* is authenticatable
   (bearer tokens / OAuth2 via a `WebhookRequestInterceptor` inside the default chain, §19g), and the
   *message* model is an **SPI** — `WebhookSigner`/`WebhookVerifier` are interfaces whose shipped
   `Default*` implementations select the scheme **per secret, by prefix**: symmetric `v1` HMAC (the
   `com.standardwebhooks` wrappers) for `whsec_` secrets and asymmetric `v2a` Ed25519 for `whsk_`/`whpk_`
   keys (§8e, §19i), with `NoOp*` impls selected by `signing-enabled` /
   `signature-verification-enabled` (§8f, §19h). The `endpoint_id` signing-content contract is carried by
   an explicit per-target `endpoint-id` field, and the asymmetric key material flows through the same
   `SecretProvider` list as HMAC secrets (so rotation and migration work unchanged). Override the bean for
   any other scheme.

---

*Status: implemented. The five library modules (`webhooks-core`, `webhooks-consumer-autoconfigure`,
`webhooks-consumer-starter`, `webhooks-producer-autoconfigure`, `webhooks-producer-starter`) are built and
pass the §16 quality gate (JaCoCo 100% line+branch, PITest 100% mutation) on `mvn clean verify -Pmutation`; the
standalone demo apps under `webhooks-sample/` sit outside that reactor. Both libraries are drop-in Spring Boot
auto-configurations that
coexist with an existing application (§14: no component scan, host-overridable beans; consumer scopes
its MVC + error handling). The consumer supports sync/async handler execution (§15) and is
performance-hardened (§18); the producer is annotation-driven (`@WebhookClient` + three
`@EnableWebhookClients` selectors, §19). Open item 2 (idempotency beyond in-memory → Redis) remains as
future work.*

---

## 18. Performance

The receive path is the hot path: for every verified delivery it verifies HMAC, parses JSON,
deserializes to the `@WebhookType` class, validates the event, builds metadata, and dispatches. The
design keeps per-request work minimal and bounded.

**All per-type/per-endpoint resources are compiled once and cached — no per-request I/O.**

- **Bean Validation metadata** (`PayloadValidator`) is resolved by Hibernate Validator from each
  `@WebhookType` class's annotations and cached by the shared `Validator`; validation is in-memory, never a
  file read.
- **Redaction plans** (`@Redact`) are reflected once per class and cached, so masking adds no
  per-call reflection (§9a).
- **`Webhook` verifier instances** are cached per secret in `WebhookVerifier` (the secret is the only
  state), so rotation-aware multi-secret verification reuses instances.
- **Handler methods** are resolved once at startup by `WebhookDispatcher` into a `type → handler` map;
  dispatch is a map lookup plus a reflective invoke.

**Single JSON parse (no double-parse).** The body is parsed **once** into the `Envelope`, whose
`data` is a Jackson `JsonNode`. Deserialization to the `@WebhookType` class uses
a strict `ObjectReader` over that same tree (`reader.treeToValue(data, cls)`), binding directly from
the already-parsed `JsonNode` — instead of the earlier `convertValue(...)`, which round-tripped through
an intermediate `TokenBuffer`. Bean Validation then runs on the resulting object, so the payload is
never re-parsed.

**Bounded caches (no unbounded growth from attacker-influenced input).** Every stateful map is
bounded by application config except where the key is operator-controlled. The one map keyed by
attacker-influenced data — `InMemoryIdempotencyGuard`, keyed by the inbound `webhook-id` (reached
only *after* signature verification) — is hardened:

- a **hard size cap** (`webhooks.consumer.in-memory-idempotency-max-size`, default 100,000) built on
  an access-order `LinkedHashMap` with `removeEldestEntry`, so once full it evicts the
  least-recently-seen id in O(1) and a flood of unique ids can never grow it past the cap;
- **TTL expiry** (10 min, aligned to the replay window) so a stale entry is treated as new rather
  than causing a false "duplicate".

**Sync vs async execution (§15).** `sync` (default) adds no threading overhead and bounds throughput
to the handler's own latency within the sender's 15–30 s budget. `async` moves handler work off the
request thread onto a **bounded** pool (`CallerRunsPolicy` back-pressure, never an unbounded queue)
with a per-handler timeout, trading the "processed" guarantee for immediate `204`s when handlers are
slow.

**Tuning knobs.**

| Knob | Default | Effect |
|------|---------|--------|
| `webhooks.consumer.max-body-size` | `256KB` | Rejects oversized bodies with `413` *before* verification/parse (§14a). |
| `webhooks.consumer.handler.mode` | `sync` | `async` returns `204` immediately and runs handlers off-thread (§15). |
| `webhooks.consumer.handler.core-pool-size` / `max-pool-size` / `queue-capacity` | `4` / `16` / `1000` | Async pool sizing and back-pressure point. |
| `webhooks.consumer.handler.timeout` | `30s` | Async per-handler timeout; a slow handler is interrupted, not left to tie up a thread. |
| `webhooks.consumer.in-memory-idempotency-max-size` | `100000` | Upper bound on retained `webhook-id`s when in-memory idempotency is enabled. |

**Baseline.** Two load harnesses ship for establishing throughput/latency on the real signed hot
path: `load-test.js` (k6, signs each request in-script, reports p50/p95/p99) and `load-test.sh`
(dependency-free `curl` + `openssl` + `xargs -P` with portable percentile summary). Run the consumer
sample on `:9091` (from `webhooks-sample/`: `mvn -pl consumer-sample spring-boot:run`) and point either harness at
it.

---

## 19. Producer — annotation-driven sending

The producer (artifact `webhooks-producer-autoconfigure`, added via `webhooks-producer-starter`) is the
symmetric **sending** side: given a `@WebhookType`-annotated event,
it builds the Standard Webhooks envelope, signs it, and POSTs it to a configured destination. Like the
consumer it is a **drop-in auto-configuration** — no component scan, every bean an explicit
`@Bean` guarded by `@ConditionalOnMissingBean` — and it ships **no storage** (no delivery history or
attempt log). It does retry transient failures *in process* (§19b), applies HTTP timeouts (§19c), and
can guard outbound URLs against SSRF (§19d); a delivery that still fails throws to the caller, and
durable cross-restart retry remains the sender's decision.

### Annotation-driven clients

A producer client is a plain interface annotated with `@WebhookClient`, modelled on Spring Cloud's
`@FeignClient`. Each method takes a single `@WebhookType`-annotated event; the method name is free (only the
parameter type matters). The producer generates a JDK dynamic-proxy bean per interface.

```java
@WebhookType("contact.created")
public class ContactCreated { private String id; private String name; /* … */ }

@WebhookClient(destination = "crm")
public interface CrmWebhooks {
    void contactCreated(ContactCreated event);   // type derived from @WebhookType
    void contactDeleted(ContactDeleted event);
}
```

```java
@Autowired CrmWebhooks crm;
crm.contactCreated(new ContactCreated(...));      // builds → signs → POSTs to the "crm" destination
```

### Opt-in registration — `@EnableWebhookClients`

Clients are registered only when the host opts in, so the library never component-scans on its own.
Three selectors are supported (they **combine**, deduplicated), mirroring `@EnableFeignClients`:

| Selector | Example | Scanning? |
|----------|---------|-----------|
| `clients` | `@EnableWebhookClients(clients = {CrmWebhooks.class})` | **None** — explicit, refactor-safe. Preferred. |
| `basePackageClasses` | `@EnableWebhookClients(basePackageClasses = CrmWebhooks.class)` | Scans the anchor's package (no magic string). |
| `basePackages` | `@EnableWebhookClients(basePackages = "com.acme.webhooks")` | Scans the string package(s). |

If none is set, the annotated class's own package is scanned. `WebhookClientRegistrar` resolves the
union and registers a `WebhookClientFactoryBean` per interface; this bean-definition glue is verified
end-to-end by context tests (one per selector) and is excluded from the mutation gate like other wiring.

### Named destinations

A destination is a named `{url, secret, additional-secrets?}` set, selected by
`@WebhookClient(destination = "...")`:

```yaml
webhooks:
  producer:
    destinations:
      crm:     { url: "https://crm.example.com/webhooks",     secret: "whsec_..." }
      billing: { url: "https://billing.example.com/webhooks", secret: "whsec_..." }
```

`additional-secrets` is optional and enables zero-downtime rotation (§19a).

### Publish pipeline (`WebhookPublisher`)

The engine behind every proxy is stateless and thread-safe. For each call it:

1. resolves the destination (unknown name → `IllegalArgumentException`);
2. derives the `type` from the event's `@WebhookType` (missing → `IllegalArgumentException`);
3. builds the envelope `{type, timestamp(ISO-8601), data}` and **serializes it once** into the exact
   byte payload that will be sent (serialization failure → `IllegalStateException`);
4. resolves the signing secret(s) via `SecretProvider<Destination>` — **fail-closed** on an empty list
   (`IllegalStateException`, never an unsigned send);
5. signs via `WebhookSigner`, which selects the scheme **per secret, by prefix** (§19i) — HMAC-SHA256
   over `id.timestamp.payload` for a `whsec_` secret (`v1`, wrapping the official `com.standardwebhooks`
   library, `Webhook` instances cached per secret), or Ed25519 over `endpoint_id.id.timestamp.payload`
   for a `whsk_` private key (`v2a`) — once per resolved secret, combining the results into one
   space-separated `webhook-signature` value (**dual-signing**, §19a);
6. sends via `WebhookTransport` with the `webhook-id` / `webhook-timestamp` / `webhook-signature`
   headers.

### Extension points (all `@ConditionalOnMissingBean`)

- **`WebhookTransport`** — the delivery SPI. The default is a chain: a `RestClientWebhookTransport`
  (POSTs synchronously via Spring's `RestClient`, validates the destination URL, turns a non-2xx
  response or transport error into a `WebhookDeliveryException` carrying the HTTP status when there was
  one) wrapped in a `RetryingWebhookTransport` (in-process exponential backoff + jitter on *retryable*
  failures; §19b). Override the whole chain to send asynchronously, through a queue, or with a different
  policy. **Only `2xx` is a successful delivery.** A `3xx` is a *failure*, not a redirect to follow —
  the default `RestClient` is built with `Redirect.NEVER`, so a redirect surfaces as a
  `WebhookDeliveryException` carrying the `3xx` status rather than being chased to another host (which
  would also bypass the destination allow-list). Per Standard Webhooks, update the webhook URL instead of
  relying on redirects.
- **`DestinationUrlValidator`** — the outbound-URL guard (SSRF protection; §19d). Default
  `AllowAllDestinationUrlValidator` (no restriction); set `security.allowed-hosts` for an
  `AllowListDestinationUrlValidator`, or supply your own bean for a custom policy.
- **`SecretProvider<Destination>`** — secret resolution. Default `ConfigDestinationSecretProvider`
  reads `destinations.<name>.secret`; override to pull from a secrets manager. This is the **same core
  SPI** the consumer uses (`SecretProvider<Endpoint>`), just bound to the producer's target type.
  Because both libraries publish a `SecretProvider<T>` bean, the producer's is registered and injected
  **by the bean name `destinationSecretProvider`** (not by the bare type) so the two never collide when
  both are on the classpath — override with `@Bean("destinationSecretProvider")`.
- **`WebhookSigner`** — message-signing SPI. The default `DefaultWebhookSigner` selects the scheme per
  secret by prefix (`whsec_` → `v1` HMAC, `whsk_` → `v2a` Ed25519; §19i). Override for a different scheme
  entirely. The `RestClient` the default transport uses and the `WebhookPublisher` itself are likewise
  overridable beans.

### Out of scope (producer)

- ❌ **No *persisted* retry** — retry is in-process only (bounded attempts, backoff on the calling
  thread; §19b). There is no outbound queue, disk spool, or scheduler, so the no-persistence rule holds;
  a delivery that exhausts its attempts throws `WebhookDeliveryException` to the caller. Plug a durable
  queue in behind `WebhookTransport` if you need at-least-once delivery across restarts.

### 19a. Secret rotation (blue/green)

The sending side mirrors the consumer's rotation story (§8a), so a secret can be rolled out across both
ends **with no dropped deliveries and in any order**. The difference is mechanical: a receiver can
*accept* any one of several secrets, but a single request is *signed*, not "accepted" — so the producer
**dual-signs**.

**Config — `additional-secrets`.** A destination declares extra signing secrets alongside its primary,
resolved by the same `SecretProvider<Destination>` SPI (the default `ConfigDestinationSecretProvider`
returns the primary first, then the additionals, dropping blanks):

```yaml
webhooks:
  producer:
    destinations:
      crm:
        url: "https://crm.example.com/webhooks"
        secret: ${CRM_WEBHOOK_SECRET}            # primary (new)
        additional-secrets:
          - ${CRM_WEBHOOK_SECRET_PREVIOUS}        # still signed with, until retired
```

**Dual-signing.** `WebhookPublisher` signs the **same** `id.timestamp.payload` once per resolved secret
and joins the results into **one space-separated `webhook-signature` header** — the multi-signature form
the Standard Webhooks spec defines. A receiver (ours or any compliant one) accepts the delivery if
*any* listed signature validates against *any* secret it trusts. With a single secret the header is just
that one signature (no separator), so the common case is unchanged.

**Zero-downtime rotation dance (both ends):**

1. **Receiver** adds the new secret to `endpoints[].additional-secrets` (now accepts old **or** new).
2. **Sender** adds the new secret to `destinations.<name>.additional-secrets` (now dual-signs old **and**
   new). Every delivery now carries both signatures; the receiver accepts on whichever it holds.
3. Promote the new secret to `secret` (primary) on both sides at leisure.
4. Drop the old secret from both `additional-secrets` lists once no traffic relies on it.

No step rejects a request, and steps 1 and 2 can happen in either order. Blank entries are discarded on
both sides, and an all-blank destination still **fails closed** (empty secret list →
`IllegalStateException`, never an unsigned send).

### 19b. In-process retry (no persistence)

A webhook delivery is a network call to a third party, so transient failures (a brief `503`, a `429`, a
dropped connection) are expected. The producer retries these **in process**, with no queue, disk, or
scheduler — the no-persistence rule (§19) is preserved. Retry lives in a `RetryingWebhookTransport` that
**decorates** the base transport, so the base stays retry-free and either layer is independently
replaceable.

**Classifier — retryable vs. fatal.** Only failures that might succeed on a repeat are retried:

| Outcome | `getStatusCode()` | Retried? |
|---------|-------------------|----------|
| `5xx` server error | present, ≥ 500 | ✅ yes |
| `429 Too Many Requests` | present, 429 | ✅ yes |
| transport error (timeout, connection refused) | empty | ✅ yes |
| any other `4xx` (e.g. `400`, `401`, `404`) | present, 400–499 | ❌ fatal — fails immediately |

A disallowed destination URL (§19d) is validated **before** the retry loop, so it is fatal and never
retried.

**Backoff.** Exponential — `initial · 2^(attempt-1)`, capped at `max-backoff` — with jitter applied so
retries don't synchronise into storms. The actual wait is sampled uniformly from
`[backoff · (1 − jitter), backoff]`. The wait is a bounded `Thread.sleep` on the calling thread.

**Config (defaults shown).** Retry is **on by default** with three attempts:

```yaml
webhooks:
  producer:
    retry:
      max-attempts: 3            # total attempts incl. the first; 1 disables retry. <1 is clamped to 1.
      initial-backoff: 500ms     # before the 2nd attempt; doubles each further attempt up to max-backoff
      max-backoff: 5s            # ceiling on any single backoff
      jitter: 0.5                # 0.0–1.0; fraction of each backoff that is randomised downward
```

After the attempts are exhausted the **last** `WebhookDeliveryException` is rethrown to the caller. To
turn retry off entirely, set `max-attempts: 1` (one attempt, then throw — the original semantics).

### 19c. HTTP timeouts

The default `RestClient` transport applies connect and read timeouts so a stalled destination cannot pin
the calling thread indefinitely. A timeout surfaces as a transport-level `WebhookDeliveryException` (no
HTTP status) and is therefore **retryable** (§19b).

```yaml
webhooks:
  producer:
    http:
      connect-timeout: 5s        # max time to establish the TCP connection
      read-timeout: 10s          # max time to wait for the response once connected
```

Supply your own `webhookProducerRestClient` bean for finer HTTP tuning (connection pooling, proxies,
TLS); the auto-configuration backs off when that bean is present.

### 19d. Destination URL guard (SSRF)

If a destination URL can ever be influenced by untrusted input, an unrestricted producer could be coerced
into requesting internal addresses (cloud metadata endpoints, private services) — a Server-Side Request
Forgery. The `DestinationUrlValidator` SPI closes that off; it is checked before every send (and, in the
retry chain, once before the loop so a rejected URL is fatal).

- **Default — `AllowAllDestinationUrlValidator`.** No restriction, because destinations come from the
  host's trusted configuration. Zero-config, backward compatible.
- **Opt-in — `AllowListDestinationUrlValidator`.** Wired automatically when `security.allowed-hosts` is
  non-empty. Only the listed hostnames may be delivered to (exact, case-insensitive match; subdomains are
  **not** implied). A URL outside the list — or one that is syntactically invalid or has no host — fails
  fast with a `WebhookDeliveryException` before any request is sent.

```yaml
webhooks:
  producer:
    security:
      allowed-hosts:             # empty (default) = no restriction
        - crm.example.com
        - billing.example.com
```

Provide your own `DestinationUrlValidator` bean for a richer policy (CIDR ranges, private-IP / DNS-rebind
blocking); the auto-configuration backs off when a bean is present.

> **Scope of the built-in guard — host allow-list only.** `AllowListDestinationUrlValidator` matches on the
> URL's **hostname string**, nothing more. It does **not** resolve DNS, does **not** block private/loopback
> IP literals (`http://169.254.169.254/…`, `http://127.0.0.1/…`) unless you omit them from the list, and is
> **not** DNS-rebind safe (a listed host whose DNS later resolves to an internal address still passes). The
> producer also refuses to follow redirects (a `3xx` is a delivery failure — §19), so a listed host cannot
> bounce the request to an internal one at the HTTP layer. For a hostile-input threat model (destination
> URLs influenced by untrusted data), treat the allow-list as one layer and add network-level SSRF controls
> — an egress proxy (e.g. Stripe's `smokescreen`), a locked-down egress subnet, or a custom validator that
> resolves and checks the target IP — rather than relying on the hostname match alone.

### 19e. Dependency vulnerability scanning

The build integrates [OWASP dependency-check](https://owasp.org/www-project-dependency-check/) to flag
dependencies with known CVEs. It is **opt-in** via the `security-scan` Maven profile rather than bound to
the default `verify`, because a full NVD scan is slow and network-bound (the 100% coverage/mutation gate
must stay fast and offline):

```bash
NVD_API_KEY=<key> mvn -Psecurity-scan verify   # an NVD API key avoids download throttling
```

The scan fails the build on a high/critical finding (CVSS ≥ 7) and skips test/provided-scope
dependencies that do not ship.

### 19f. Log-safe destination URLs

A destination URL can legitimately carry a secret in two places that must never reach a log sink: the
**userinfo** (`https://user:token@host/…`) and the **query string** (`…?access_token=…`). The producer
never logs a raw destination URL:

- Every URL the transport formats itself (retry/abandon log lines, the `WebhookDeliveryException` message)
  is rendered through `SafeUrl.sanitize`, which keeps the useful parts (scheme, host, port, path) and
  drops userinfo and query. An unparseable URL is reduced by a textual best-effort strip that removes
  **both** a `user:token@` userinfo and anything from the first `?`, so a malformed value cannot leak
  either secret component.
- The underlying HTTP client echoes the full raw request URL into its own exception messages (e.g.
  `I/O error on POST request for "https://user:token@host/…": …`). Those messages are chained into the
  `WebhookDeliveryException` and then logged, so before a cause message is concatenated into a log line it
  is passed through `SafeUrl.redactMessage`, which replaces the raw URL embedded in the text with its
  sanitized form. This closes the leak at both the point the exception is wrapped and the point it is
  logged, so a custom transport that throws a message carrying the raw URL is covered too.

The real, unmodified URL is always used for the actual delivery; sanitization is a logging aid only.

### 19g. Transport authentication (bearer tokens / OAuth2)

Authenticating the **HTTP transport** (an `Authorization: Bearer` token, typically from an **OAuth2
client-credentials** grant) is a separate, additive layer on top of the Standard Webhooks HMAC signature:
transport auth authenticates the *caller*, the signature authenticates the *message*. It needs **no core
change** to the toolkit — the producer side adds a `WebhookRequestInterceptor` bean (applied inside the
default transport chain, so retry and the SSRF guard are preserved) and the consumer side is a standard
Spring Security resource-server chain.

The full both-sides walkthrough (producer bearer-token transport, consumer resource-server filter chain,
and the order-of-defences reasoning) now lives in its own guide:
**[Transport authentication](../guides/transport-authentication.md)**.

`WebhookRequestInterceptor` is the **single outbound-request-shaping strategy**: every concern that needs
to add headers or otherwise mutate the outbound request before it is signed and sent — transport auth,
multi-tenancy routing, and **trace/correlation headers (§9b)** — composes through the same interface. The
toolkit ships no built-in bean for any of them; the application contributes the interceptor(s) it needs,
because each depends on application-specific context (credentials, tenant, the trace id in its own `MDC`).

> An asymmetric / JWS / JWT **message** model (public-key authenticity instead of a shared HMAC secret)
> would replace the *signing scheme* itself, not the transport — that is tracked separately in §17.

### 19h. Disabling message signing

Signing is a **pluggable SPI with a disable switch**, the producer counterpart of §8f. `WebhookSigner`
is an interface; two implementations ship, wired `@ConditionalOnMissingBean` from configuration:

| Implementation | When | Behaviour |
| --- | --- | --- |
| `DefaultWebhookSigner` | **Default** (`signing-enabled=true`) | Signs each delivery, selecting `v1` (HMAC) or `v2a` (Ed25519) per secret by prefix (§19i), producing the `webhook-signature` header (dual-signing on rotation, §19a). |
| `NoOpWebhookSigner` | `signing-enabled=false` | Produces no signature; `isEnabled()` is `false`, which tells `WebhookPublisher` to skip the secret requirement and omit the `webhook-signature` header entirely. |

```yaml
webhooks:
  producer:
    signing-enabled: false   # default: true
```

When disabled: no destination secret is required and deliveries go out **unsigned** (id + timestamp
headers only). Override the bean with your own `@Bean WebhookSigner` for a different *scheme* (rather than
disabling).

> **Security note.** Disabling signing removes message authentication — a receiver that verifies will
> reject the unsigned deliveries. Only disable when the receiver authenticates the transport instead
> (OAuth2 bearer token / mTLS, §19g). Signing and transport auth are complementary; prefer keeping both.

---

### 19i. Asymmetric signing — Ed25519 `v2a` (`whsk_`)

The producer counterpart of §8e. To sign a destination's deliveries with the Standard Webhooks
asymmetric `v2a` scheme, configure its `secret` as an Ed25519 **private** key (`whsk_…`) and give it an
`endpoint-id`. `DefaultWebhookSigner` sees the `whsk_` prefix and routes to `Ed25519WebhookSigner`; a
`whsec_` secret stays on `v1`. The receiver verifies with only the matching **public** key (`whpk_…`),
so the private key — the only thing that can produce a valid signature — never leaves the sender.

**What is signed.** Where `v1` signs `msg_id.timestamp.payload`, `v2a` signs
`endpoint_id.msg_id.timestamp.payload`, then Ed25519-signs the SHA-256 of that string, emitting
`v2a,<base64 signature>` in the (space-separated) `webhook-signature` header (§8e has the exact formula).
The `endpoint-id` is **never sent on the wire**; the receiver is told the same value out of band and
rebuilds the signed content with it when verifying — which is what prevents a signature made for one
endpoint from verifying at another that trusts the same public key.

```yaml
webhooks:
  producer:
    destinations:
      crm:
        url: "https://crm.example.com/webhooks"
        endpoint-id: ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb   # required for v2a; must not contain '.'
        secret: ${CRM_WEBHOOK_PRIVATE_KEY}             # whsk_… — the private key; never share it
        additional-secrets:                            # a second whsk_ (rotation) or a whsec_ (migration)
          - ${CRM_WEBHOOK_PRIVATE_KEY_PREVIOUS}
```

**The `endpoint-id` must match on both sides**, and is per-destination. Give the receiver the matching
`whpk_` public key and the **same** `endpoint-id` (§8e). For a **fan-out** where one producer sends to
many receivers, each `destination` carries its own `endpoint-id` and key; two destinations may share a
key pair by repeating the same `whsk_` value. A single receiver path fronting **several independent
producers** is out of scope for the scheme — configure one endpoint per producer (each with its own
`endpoint-id` and the matching producer's public key) rather than trying to multiplex one endpoint.

**Mixing and rotation.** Because scheme selection is per secret, a destination can carry both a `whsec_`
and a `whsk_` secret during a migration; the publisher dual-signs and the header carries a `v1,…` and a
`v2a,…` side by side, letting a receiver cut over at its own pace (§19a). Rotating a `v2a` key pair is the
same blue/green dance with two `whsk_` keys.

**Startup validation (§19d).** `DestinationValidator` fails the context fast and loud if a `v2a`
destination is misconfigured: a missing `endpoint-id` when a `whsk_` key is present, an `endpoint-id`
containing the `.` delimiter, a malformed private key, or a `whpk_` **public** key configured where the
private key belongs (a producer must sign with the private key). Each failure is rendered by a Spring
Boot `FailureAnalyzer` as a clean `APPLICATION FAILED TO START` report naming the destination and the
fix, never the key material. An `endpoint-id` set on a purely `v1` destination is accepted but logs a
warning that it will be ignored.

> **Key generation & interoperability.** Generate a `whsk_`/`whpk_` Ed25519 pair with any Standard
> Webhooks SDK (the key body is the Base64 of the raw 32-byte key after the prefix). `v2a` is implemented
> with JDK-native Ed25519 (no extra dependency) and verified byte-for-byte against the published spec
> worked example, so it interoperates with any conforming receiver. See
> `guides/verification-and-security.md` for an end-to-end producer→consumer setup.

---

## 20. Reliability & delivery guarantees

Reliability for a webhook toolkit is a **distributed** property shared between sender, network, and
receiver — and in this toolkit it is deliberately bounded by the **no-storage constraint** (§1, §11).
Durable, cross-restart delivery fundamentally needs state; this toolkit holds none, so its own guarantees
are **in-process only**. This section states exactly what is and is not guaranteed, the failure modes that
follow, and the patterns that make a deployment reliable.

### 20.1 What *is* guaranteed (in-process)

- **Producer — bounded retry of transient failures.** `5xx`, `429`, and transport errors (timeout,
  connection refused) are retried with exponential backoff + jitter, on by default (§19b). A delivery
  that exhausts its attempts (or hits a fatal `4xx`) is routed to the `WebhookDeliveryListener` hook
  (§20.5) and then throws `WebhookDeliveryException` to the caller — it **fails loud, never silent**.
- **Producer — HTTP timeouts.** Connect/read timeouts (§19c) ensure a stalled destination cannot pin the
  calling thread indefinitely; a timeout is classified as retryable.
- **Producer — fail-closed signing.** With signing enabled (the default), an all-blank secret yields an
  empty signature list → `IllegalStateException`, never an *accidentally* unsigned send (§8a, §19a).
  Deliveries are sent unsigned only when signing is **explicitly** disabled (§19h).
- **Consumer — correct status-code contract.** The HTTP status the consumer returns is the *only* thing
  the sender reacts to, and it is driven precisely by the outcome (§12): a handler that throws → non-2xx →
  the **sender** retries. The sender's own retry machinery is the primary durability mechanism.
- **Consumer — `sync` means processed.** In the default `sync` mode the `2xx` is returned only after the
  handler completes (§15), so a `2xx` is an honest "processed" acknowledgement.
- **Consumer — replay resistance (opt-in).** The timestamp replay window (§8b) lets a receiver reject
  stale/replayed requests without storage.
- **Consumer — bounded resource use.** Body-size limit before verification (§14a), bounded async pool
  with per-handler timeout and daemon threads (§15), and a bounded in-memory idempotency cache (§11) —
  none can grow memory without limit.

### 20.2 What is *not* guaranteed (the no-storage boundary)

Because nothing is persisted, the following are explicitly **out of scope** of the toolkit itself:

- **No at-least-once across restarts.** A JVM crash loses in-flight producer retries (they live on the
  calling thread) and any **queued** consumer async tasks (`async` mode acknowledges with `204` *before*
  the handler runs; a crash before it runs drops that delivery — §15).
- **No exactly-once / cross-instance idempotency.** The in-memory `IdempotencyGuard` (§11) is
  best-effort within one instance's lifetime; it does not survive restarts or coordinate across
  instances. True exactly-once requires an external store (Redis) — the one open item in §17.
- **No ordering guarantee.** The toolkit never promises that events are processed in the order they
  were sent. In **`async` mode** handlers run concurrently on a pool (and inline under back-pressure),
  so A → B can finish B-first (§15). In **`sync` mode** each delivery completes before its `2xx`, but
  ordering *across concurrent requests* is still driven by the sender and the network, not the toolkit —
  HTTP webhooks carry no cross-request ordering contract. A handler that depends on order must be made
  order-tolerant (reconcile against an event version/timestamp) or serialise per key in the host's own
  infrastructure.
- **No durable outbox.** The producer does not couple delivery to a database transaction, so a "business
  change committed but webhook never sent" gap is possible if the process dies between the two.
- **No dead-letter *store*.** The toolkit does not itself persist failed deliveries for later replay — it
  has nowhere to put them. It *does* expose a **failure hook** (§20.5) that hands every abandoned delivery
  and every dropped async handler to a listener, so a host can build that store; but out of the box an
  abandoned delivery is only rethrown/logged, not retained.
- **No endpoint lifecycle / stateful status handling** (deliberate deviations from the
  [Standard Webhooks spec §8](./STANDARD_WEBHOOKS.md#8-delivery-success--failure-http-status-handling),
  which are *producer-fleet* concerns needing persistence):
  - **`410 Gone` is not auto-disable.** The spec suggests a `410` means "consumer no longer wants
    webhooks — disable the endpoint". The toolkit treats `410` as a generic fatal `4xx` (abandon +
    dead-letter + rethrow); it holds no endpoint registry to flip to "disabled". Disabling is a host
    decision driven off the `WebhookDeliveryListener` (§20.5), which receives the `410` cause.
  - **`Retry-After` is honored as a backoff *floor*.** On a retryable `429`/`503` whose response
    carries a `Retry-After` header, the next wait is `max(exponential-backoff + jitter, Retry-After)`,
    with the `Retry-After` value itself clamped to `max-backoff` so an over-eager server can never push
    a single wait past the configured ceiling. Both header forms are parsed (delta-seconds and
    HTTP-date); a negative/past value clamps to zero. This needs no storage and is on by default — it
    only ever *raises* a wait, never lowers it. See §20.6/A1 and the
    [Reliability guide → Honoring Retry-After](../guides/reliability.md#honoring-retry-after).

### 20.3 Failure modes at a glance

| Event | Producer | Consumer |
| --- | --- | --- |
| Transient `5xx`/`429`/timeout | Retried in process (§19b); throws after exhaustion | Sender retries on the non-2xx it received |
| Fatal `4xx` (e.g. `401`, `404`) | Not retried; throws immediately | — |
| `410 Gone` | Treated as a generic fatal `4xx` (abandon + dead-letter + throw); **not** auto-disabled (§20.2) | — |
| `429`/`503` with `Retry-After` | Retried on the toolkit's own backoff, using `Retry-After` as a floor: `max(exponential, Retry-After)`, clamped to `max-backoff` (§20.6/A1) | — |
| Handler throws (`sync`) | — | `500` → sender retries |
| Handler throws (`async`) | — | Sender already got `204`; failure is **logged at `ERROR` and routed to the `HandlerFailureListener`** (§20.5), so it is visible — but the delivery is still not retried |
| Duplicate delivery | Sender may resend | Deduped only if in-memory guard is enabled *and* same instance; otherwise handler must be idempotent |
| **Process crash mid-flight** | In-flight retries **lost** | Queued `async` tasks **lost**; in-memory dedupe state **lost** |

### 20.4 How to make a deployment reliable

Within the no-storage rule — recommended, zero new dependencies:

1. **Make handlers naturally idempotent and run `sync` (the recommended default).** Then a `2xx` truly
   means "processed", and the sender's retry is your durability. This is the single biggest lever and
   costs nothing.
2. **Key off `webhook-id`.** The producer stamps a unique id per delivery; a handler that upserts by it
   is safe against the sender's at-least-once retries.
3. **Enable the replay window** (§8b) for replay-attack resistance.
4. **Tune producer retry** (§19b) to the destination's SLA, and **alert on the existing
   `webhook.send` / `webhook.receive` metrics** (§13).

Beyond the no-storage rule — when at-least-once or exactly-once is actually required, each fits behind an
**existing SPI**, so no core rewrite is needed:

5. **Durable producer delivery** — implement `WebhookTransport` to enqueue to a broker (Kafka/SQS/Rabbit)
   or a **transactional outbox** written in the same DB transaction as the business change, with a relay
   that drains it. This is the designed extension point for at-least-once that survives restarts.
6. **Cross-instance idempotency** — implement `IdempotencyGuard` over Redis (§17 open item) for true
   process-once across instances and restarts.
7. **Durable consumer async** — replace `HandlerExecutor` with one that persists the task before ack, so
   a crash does not drop a queued delivery.
8. **Operational resilience** — a circuit breaker behind `WebhookTransport` (so one dead destination
   can't exhaust threads) and a dead-letter hook on retry exhaustion (so failures are captured, not just
   logged).

> **In one line:** the toolkit guarantees *bounded, fail-loud, in-process* reliability and a correct
> status-code contract; *durable* reliability is delivered by plugging a store in behind the
> `WebhookTransport` / `IdempotencyGuard` / `HandlerExecutor` SPIs, by design.

### 20.5 Failure hooks (dead-letter capture)

The toolkit holds no storage, but it does not have to let a failure vanish. Two **no-op-by-default**
listener SPIs give a host the single point at which an abandoned delivery or a dropped async handler can
be captured — persisted to a dead-letter store, alerted on, or enqueued for manual replay — without the
core taking on any state. Both are `@ConditionalOnMissingBean`, so publishing your own `@Bean` replaces
the no-op.

**Producer — `WebhookDeliveryListener`.** Fires **once per delivery**, at the moment the toolkit stops
trying (a fatal `4xx`, or a retryable failure whose `max-attempts` are exhausted — see
`RetryingWebhookTransport`), immediately *before* the `WebhookDeliveryException` is rethrown to the
caller. The callback receives a `DeliveryFailure` carrying the send-ready signed request (url, headers,
body), the attempt count, and the last exception — everything needed to persist and replay it later.
Intermediate retry attempts are not reported here (they are logged at `WARN`); this is the final-failure
signal only.

```java
@Bean
WebhookDeliveryListener deadLetter(MyDeadLetterStore store) {
    return failure -> store.save(failure.url(), failure.headers(), failure.body(),
                                 failure.attempts(), failure.cause());
}
```

**Consumer — `HandlerFailureListener`.** An `async`-only concern (in `sync` mode a thrown handler already
becomes the non-2xx the sender retries on, so there is nothing hidden to report). Because the `204` has
already been sent, an `async` handler that throws or times out can no longer be surfaced to the sender;
the executor **logs it at `ERROR` and routes it to the listener** — `onHandlerFailed(type, cause)` for a
thrown exception, `onHandlerTimeout(type)` when the per-handler timeout interrupted it.

```java
@Bean
HandlerFailureListener handlerFailures(MyAlerts alerts) {
    return new HandlerFailureListener() {
        public void onHandlerFailed(String type, Throwable cause) { alerts.raise(type, cause); }
        public void onHandlerTimeout(String type)                 { alerts.timeout(type); }
    };
}
```

**Isolation.** Both hooks are defensive: a listener that itself throws is caught and logged, and **cannot**
change the caller-visible outcome (the producer still rethrows the original `WebhookDeliveryException`;
the async executor still moves on). A dead-letter sink that is down must never turn one failure into two.

> These hooks make failures **visible and capturable**; they are the seam on which a durable dead-letter
> store is built, but they are not themselves that store — they hold nothing. Pairing a
> `WebhookDeliveryListener` with a real sink (a DB table, an SQS queue) is the minimal path to "no
> delivery is ever silently lost".

### 20.6 Spec-alignment backlog

Items from the [Standard Webhooks spec](./STANDARD_WEBHOOKS.md) that align the toolkit more closely with
the spec and need **no persistence**, so they do not violate the no-storage design (§1, §11). **A1–A4 are
now implemented** (status column links the code and the living example that documents each); **A5 is
deferred** for the reason noted below.

> **Walkthroughs with diagrams and worked examples:** A1 and the dead-letter hooks are covered in the
> [Reliability guide](../guides/reliability.md); A2–A4 (lag logging, `webhook-id` correlation, opt-in
> metrics) in the [Observability guide](../guides/observability.md).

| # | Spec | Item | Status |
| --- | --- | --- | --- |
| A1 | [§8](./STANDARD_WEBHOOKS.md#8-delivery-success--failure-http-status-handling) / [§9](./STANDARD_WEBHOOKS.md#9-retry-schedule) | **Honor `Retry-After`.** On a retryable failure (`429`/`503`) whose response carries `Retry-After`, use it as the backoff floor (clamped to `max-backoff`). | **Implemented.** `RestClientWebhookTransport.parseRetryAfter` parses both header forms onto `WebhookDeliveryException.getRetryAfter()`; `RetryingWebhookTransport.backoffWithRetryAfter` applies it as `max(exponential, Retry-After)`, clamped to `max-backoff`. Living examples: `RestClientWebhookTransportTest` (header parsing) and `RetryingWebhookTransportTest` (`honorsRetryAfterAsABackoffFloor…`, `clampsRetryAfterToTheMaxBackoff`). Docs: [README → Producer retry](../../README.md#producer-retry). |
| A2 | [§11](./STANDARD_WEBHOOKS.md#11-tracing--logging-consumer-side) | **Delivery-lag / event-lag logging.** Add `now − webhook-timestamp` (delivery lag) and `now − body.timestamp` (event lag) to the verified-success log line. | **Implemented.** `ReceiveObservabilityRecorder` logs `Webhook verified type=… deliveryLag=…ms eventLag=…` (`eventLag=n/a` when the body carried no timestamp). Living example: `WebhookReceiveControllerTest` (asserts the `deliveryLag=…ms eventLag=…` and `eventLag=n/a` log lines). Docs: [README → Observability](../../README.md#observability). |
| A3 | [§11](./STANDARD_WEBHOOKS.md#11-tracing--logging-consumer-side) | **`webhook-id` as the primary correlation key.** The stable `webhook-id` (constant across retries — not a random per-request id) is available to every handler and log line. | **Implemented, app-owned (§9b).** The stable `webhook-id` is surfaced on `WebhookMetadata.webhookId()`; the toolkit writes nothing to the SLF4J `MDC`, leaving the application free to bind it (and any trace id) under whatever key its log pattern uses. The sample apps show the full end-to-end pattern. Living example: the sample apps' `TraceContextFilterTest` (binds `webhook-id` + trace id to the `MDC` before the controller) and `TraceHeaderWebhookInterceptorTest` (producer-side propagation). Docs: [README → Observability](../../README.md#observability). |
| A4 | [§11](./STANDARD_WEBHOOKS.md#11-tracing--logging-consumer-side) | **Consumer metrics.** Opt-in `MeterRegistry` counters/timers: `webhook.received{type}`, `webhook.verification.failed{reason}`, `webhook.handle.duration{type}`, `webhook.delivery.lag`, `webhook.event.lag`. | **Implemented.** `WebhookMetrics` SPI with a no-op default and `MicrometerWebhookMetrics`, auto-wired only when a `MeterRegistry` bean exists (`micrometer-core` optional). Living examples: `MicrometerWebhookMetricsTest` and `NoOpWebhookMetricsTest`; the meters are also exercised end-to-end in `WebhookReceiveControllerTest`. Docs: [README → Observability](../../README.md#observability). |
| A5 | [§6 (extension)](./STANDARD_WEBHOOKS.md#endpoint-verification-challenge-extension) | **Endpoint verification challenge.** The `webhook-challenge` / `webhook-challenge-response` handshake. | **Deferred: requires a `standardwebhooks` version exposing `endpointVerificationResponse(...)`.** The resolved library (`1.2.0`) exposes only `verify`/`sign`/constructors on `Webhook`/`WebhookBase` — not the challenge methods the spec example references. Rather than invent a non-interoperable signing scheme, this item waits on a library release that provides the canonical handshake API. |

**Out of scope (needs persistence — see §20.2, not this backlog):** `410 Gone` → auto-disable endpoint;
durable outbox; dead-letter *store*; at-least-once across restarts; cross-instance idempotency; and the
multi-day retry schedule of [§9](./STANDARD_WEBHOOKS.md#9-retry-schedule) (incompatible with a
calling-thread `Thread.sleep` backoff — that is a queue's responsibility, reached by replacing the
`WebhookTransport` SPI).
