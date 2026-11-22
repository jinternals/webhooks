# Webhooks Toolkit for Spring Boot

[![CI](https://github.com/jinternals/webhooks/actions/workflows/ci.yml/badge.svg)](https://github.com/jinternals/webhooks/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jinternals/webhooks-core?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.jinternals)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F)
[![Standard Webhooks](https://img.shields.io/badge/Standard_Webhooks-compliant-8A2BE2)](https://www.standardwebhooks.com/)

> [!NOTE]
> **Status: pre-1.0.** The toolkit is feature-complete and fully tested, but until 1.0 the public API and
> configuration properties may change between minor versions. Breaking changes are always called out in the
> [CHANGELOG](./CHANGELOG.md).

A [Standard Webhooks](https://www.standardwebhooks.com/)–compliant toolkit for sending and receiving
webhooks in Spring Boot applications. The toolkit provides two drop-in auto-configuration libraries:

- The **consumer** verifies, deserializes, and dispatches inbound webhooks. You declare endpoints and
  secrets in configuration and write a `@WebhookType` event class and a `@WebhookHandler` method; the library
  performs signature verification, type-based deserialization, per-type Bean Validation,
  redaction-safe logging, and observability.
- The **producer** sends outbound webhooks. You declare a `@WebhookClient` interface; the library
  builds the Standard Webhooks envelope from a `@WebhookType` event, signs it, and delivers it.

Both libraries follow Standard Webhooks for headers, the HMAC-SHA256 signature scheme, and the
timestamp-based replay window. The replay window is **disabled by default** and configurable; see
[Replay window](#replay-window).

> **📖 Full documentation lives in [`docs/`](./docs/README.md)** — a complete, cross-linked site with
> diagrams: [Getting started](./docs/getting-started.md) · [Architecture](./docs/architecture.md) ·
> [Consumer](./docs/guides/consumer.md) / [Producer](./docs/guides/producer.md) guides ·
> [Verification & security](./docs/guides/verification-and-security.md) ·
> [Reliability](./docs/guides/reliability.md) · [Observability](./docs/guides/observability.md) ·
> [Configuration reference](./docs/reference/configuration.md) · [Runbook](./docs/operations/runbook.md) ·
> [FAQ](./docs/operations/faq.md). This README is the quick reference; the docs site is the home.

## Table of contents

- [Design scope](#design-scope)
- [Modules](#modules)
- [Requirements](#requirements)
- [Consumer quick start](#consumer-quick-start)
- [Producer quick start](#producer-quick-start)
- [Configuration reference](#configuration-reference)
  - [Feature toggles](#feature-toggles)
  - [Tunable values](#tunable-values)
  - [Replaceable beans](#replaceable-beans)
  - [Observability](#observability)
  - [HTTP status codes](#http-status-codes)
- [Replay window](#replay-window)
- [Secret rotation](#secret-rotation)
- [Producer retry](#producer-retry)
- [Async handler execution](#async-handler-execution)
- [Failure hooks (dead-letter capture)](#failure-hooks-dead-letter-capture)
- [Running the samples](#running-the-samples)
- [Quality gate](#quality-gate)
- [Further reading](#further-reading)
- [Contributing](#contributing)
- [License](#license)

## Design scope

The toolkit intentionally holds **no state**: no delivery history, no attempt log, and no retry
persistence. The producer retries transient failures *in process* (bounded attempts with backoff on the
calling thread; see [Producer retry](#producer-retry)) but never spools to a queue or disk — once the
attempts are exhausted it throws `WebhookDeliveryException` to the caller, and the consumer returns the
appropriate HTTP status code for the sender to act on. Durable, cross-restart retry stays the sender's
responsibility (plug a queue in behind the `WebhookTransport` bean). The timestamp-based replay window
defined by Standard Webhooks is what allows a consumer to resist captured-request replay without storage;
it is **disabled by default** in this toolkit and must be enabled explicitly (see
[Replay window](#replay-window)).

## Modules

The project is a Maven reactor of five library modules — `webhooks-core` plus a starter + autoconfigure pair for each side. Downstream apps depend on the **starter** artifacts, which transitively bring in the matching autoconfigure module and its runtime dependencies. The runnable demos live separately under `webhooks-sample/` (outside the library reactor).

| Module | Description |
| --- | --- |
| `webhooks-core` | Shared wire model, the `@WebhookType` and `@Redact` annotations, the redactor, the type registry, and the generic `SecretProvider<T>` SPI. |
| `webhooks-consumer-autoconfigure` | The receiver auto-configuration (added via `webhooks-consumer-starter`). |
| `webhooks-consumer-starter` | Thin Spring Boot starter — the dependency receiver apps add; pulls in `webhooks-consumer-autoconfigure` and its runtime deps. |
| `webhooks-producer-autoconfigure` | The annotation-driven sender auto-configuration (`@WebhookClient`; added via `webhooks-producer-starter`). |
| `webhooks-producer-starter` | Thin Spring Boot starter — the dependency sender apps add; pulls in `webhooks-producer-autoconfigure` and its runtime deps. |
| `webhooks-sample/producer-sample`, `webhooks-sample/consumer-sample` | Two standalone Spring Boot apps (under `webhooks-sample/`, outside the library reactor) demonstrating each side against the published starters. |

## Requirements

| Requirement | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.x |
| Maven | 3.9 or later |

## Consumer quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.jinternals</groupId>
    <artifactId>webhooks-consumer-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Auto-configuration wires the library automatically; no component scan of the library is required.

> **Coexistence with an existing application.** The library never component-scans. All beans are
> explicit and guarded by `@ConditionalOnMissingBean`, its MVC interceptor is scoped to the configured
> webhook paths, and its error handling is scoped to the webhook controller, so it does not intercept
> your own controllers. Web beans are activated only in a servlet web application. See
> [SPEC §14](./docs/reference/SPEC.md#14-module--package-layout).

### 2. Configure endpoints and secrets

```yaml
webhooks:
  consumer:
    endpoints:
      - path: /webhooks/crm                 # one URL, several event types
        secret: ${CRM_WEBHOOK_SECRET}
        types: [contact.created, contact.updated, contact.deleted]

      - path: /webhooks/billing/invoice-paid  # one URL, a single event type
        secret: ${BILLING_WEBHOOK_SECRET}
        types: [invoice.paid]
```

The `types` list is an optional allow-list; omit it to accept any known `@WebhookType`. A type an endpoint
does not accept is handled by `unknown-type-policy`, which returns `204` (`ack-and-ignore`, the
default) or `400` (`reject`).

### 3. Define a `@WebhookType` event

The event class maps the webhook's `data` object. Mark sensitive fields with `@Redact` so they are
masked in logs.

```java
@WebhookType("contact.created")
public class ContactCreated {
    private String id;
    private String fullName;

    @Redact                 // rendered as ***REDACTED*** by the redaction-aware renderer
    private String email;

    // getters and setters
}
```

> **Strict deserialization.** The event `data` object is read with `FAIL_ON_UNKNOWN_PROPERTIES`
> enabled, so a payload carrying a field your `@WebhookType` class does not declare is rejected with a
> `400` (SPEC §6a). Do **not** add `@JsonIgnoreProperties(ignoreUnknown = true)` to the event class:
> a class-level annotation wins over the reader's feature and would silently re-enable leniency,
> defeating the strict contract. If you genuinely need to accept forward-compatible extra fields,
> that is a deliberate per-type opt-out, not the default.

### 4. Write a `@WebhookHandler`

Declare handlers on any `@Component`. Three method signatures are supported.

```java
@Component
public class ContactHandlers {

    @WebhookHandler
    public void onCreated(ContactCreated event) { ... }                        // event only

    @WebhookHandler
    public void onUpdated(ContactUpdated event, WebhookMetadata meta) { ... }   // event and metadata

    @WebhookHandler
    public void onDeleted(WebhookMetadata meta, ContactDeleted event) { ... }   // metadata and event
}
```

`WebhookMetadata` carries the event type, both timestamps (attempt and occurrence), the `webhook-id`,
the endpoint path, the client IP, a redacted headers map, and a provider-specific `attributes` map. Use
`meta.redactedEvent()` for log-safe output. The toolkit does not carry a trace id here or write to the
SLF4J `MDC` — correlation is the application's concern; see
[SPEC §9b](./docs/reference/SPEC.md#9b-correlation--trace-context-app-owned).

The generic receive endpoint, signature verification, deserialization, and dispatch are all provided
by the library.

## Producer quick start

The producer is the symmetric sending side. Declare a `@WebhookClient` interface, inject it, and call
it; the library builds the Standard Webhooks envelope from the `@WebhookType` event, signs it, and sends it.
Like the consumer, it is a drop-in auto-configuration with no component scan and fully overridable
beans, and it holds no storage (no delivery history or attempt log). It retries transient failures *in
process* (see [Producer retry](#producer-retry)), applies HTTP timeouts, and can guard outbound URLs
against SSRF — a delivery that still fails throws `WebhookDeliveryException` to the caller. Each send is
instrumented with a Micrometer `webhook.send` observation (see [Observability](#observability)).

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.jinternals</groupId>
    <artifactId>webhooks-producer-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure named destinations

A destination is a named pair of a URL and a signing secret.

```yaml
webhooks:
  producer:
    destinations:
      crm:     { url: "https://crm.example.com/webhooks",     secret: ${CRM_WEBHOOK_SECRET} }
      billing: { url: "https://billing.example.com/webhooks", secret: ${BILLING_WEBHOOK_SECRET} }
```

To rotate a destination's secret without downtime, see [Secret rotation](#secret-rotation).

### 3. Declare a `@WebhookClient` interface

Each method takes a single `@WebhookType`-annotated event. The method name is free; only the parameter type
is significant, and the event's `@WebhookType` becomes the envelope's `type`.

```java
@WebhookClient(destination = "crm")
public interface CrmWebhooks {
    void contactCreated(ContactCreated event);   // @WebhookType("contact.created")
    void contactDeleted(ContactDeleted event);
}
```

### 4. Enable clients with `@EnableWebhookClients`

The library does not scan on its own; you enable the clients explicitly. Three selectors are supported
and combine (deduplicated), mirroring `@EnableFeignClients`.

```java
// Explicit and refactor-safe, with no scanning (recommended):
@EnableWebhookClients(clients = {CrmWebhooks.class, BillingWebhooks.class})

// Type-safe package anchor (scans the package CrmWebhooks lives in):
@EnableWebhookClients(basePackageClasses = CrmWebhooks.class)

// String-based package scan:
@EnableWebhookClients(basePackages = "com.acme.webhooks")
```

If no selector is set, the annotated class's own package is scanned.

### 5. Inject and call

```java
@Autowired
CrmWebhooks crm;

crm.contactCreated(new ContactCreated(...));   // builds, signs, and POSTs to the "crm" destination
```

Transient failures are retried in process by default (see [Producer retry](#producer-retry)); to send
asynchronously, through a queue, or with a different delivery policy, override the `WebhookTransport`
bean. To add **transport authentication** on the producer — e.g. an `Authorization: Bearer` token from an
**OAuth2 client-credentials** grant, alongside (not instead of) the HMAC signature — publish a
`WebhookRequestInterceptor` instead, so retry and the SSRF guard stay in place; on the consumer the matching
token check is a standard Spring Security resource-server chain in front of the webhook endpoint. Both sides
are shown end-to-end in the [Transport authentication guide](./docs/guides/transport-authentication.md). To source
secrets from a secrets manager, override `SecretProvider<Destination>` (the bean named
`destinationSecretProvider`). Both are `@ConditionalOnMissingBean`. See
[SPEC §19](./docs/reference/SPEC.md#19-producer--annotation-driven-sending) and the
[Transport authentication guide](./docs/guides/transport-authentication.md).

## Configuration reference

All behavior is driven by `webhooks.consumer.*` and `webhooks.producer.*` properties together with a
set of overridable beans. The following tables are the complete configuration surface: the features you
can enable or disable, the values you can tune, and the beans you can replace. Defaults are chosen so
that the libraries operate with no configuration beyond endpoints, destinations, and secrets.

### Feature toggles

Flags that default to *enabled* can be turned off; features that default to *disabled* are opt-in.

| Feature | Default | Property |
| --- | --- | --- |
| Receive observations (`webhook.receive` metric and span) | Enabled (with actuator present) | `management.observations.enable.webhook.receive=false` to disable |
| Send observations (`webhook.send` metric and span) | Enabled (with actuator present) | `management.observations.enable.webhook.send=false` to disable |
| All observations | Enabled (with actuator present) | `management.observations.enable=false` to disable |
| Startup secret validation | Enabled | `webhooks.consumer.validate-secrets-on-startup=false` to disable |
| Startup destination validation | Enabled | `webhooks.producer.validate-destinations-on-startup=false` to disable |
| Inbound signature verification | **Enabled** (`v1` HMAC / `v2a` Ed25519) | `webhooks.consumer.signature-verification-enabled=false` to disable (only behind transport auth). See [SPEC §8f](./docs/reference/SPEC.md#8f-disabling-message-security). |
| Outbound message signing | **Enabled** (`v1` HMAC / `v2a` Ed25519) | `webhooks.producer.signing-enabled=false` to disable (only behind transport auth). See [SPEC §19h](./docs/reference/SPEC.md#19h-disabling-message-signing). |
| In-memory idempotency | Disabled | `webhooks.consumer.in-memory-idempotency=true` to enable |
| Asynchronous handler execution | Disabled (`sync`) | `webhooks.consumer.handler.mode=async` to enable |
| Per-type payload validation | Always on | Declare Bean Validation constraints on the `@WebhookType` class (`@NotBlank`, `@Positive`, …); no config |
| Unknown-type rejection | Disabled (`ack-and-ignore`) | `webhooks.consumer.unknown-type-policy=reject` to enable |
| Replay window (timestamp freshness) | **Enabled** (`5m` tolerance) | `webhooks.consumer.replay-window-enabled=false` to disable. See [Replay window](#replay-window) and [SPEC §8b](./docs/reference/SPEC.md#8b-replay-window-timestamp-freshness). |
| Consumer secret rotation | Disabled (single secret) | `webhooks.consumer.endpoints[].additional-secrets=[...]` to enable |
| Producer secret rotation | Disabled (single secret) | `webhooks.producer.destinations.<name>.additional-secrets=[...]` to enable |
| Producer retry (in-process) | **Enabled** (3 attempts) | `webhooks.producer.retry.max-attempts=1` to disable (one attempt, then throw). See [Producer retry](#producer-retry) and [SPEC §19b](./docs/reference/SPEC.md#19b-in-process-retry-no-persistence). |
| Producer destination URL allow-list (SSRF guard) | Disabled (no restriction) | `webhooks.producer.security.allowed-hosts=[...]` to enable. See [SPEC §19d](./docs/reference/SPEC.md#19d-destination-url-guard-ssrf). |

### Tunable values

| Property | Default | Description |
| --- | --- | --- |
| `@WebhookType` discovery | *(auto)* | Discovered automatically by scanning the host app's `@SpringBootApplication` package(s); override with a custom `@Bean WebhookTypeRegistry`. |
| `webhooks.consumer.endpoints[]` | *(required)* | Endpoints, each `{ path, secret?, endpoint-id?, types?, additional-secrets? }`. Prefix selects the scheme (`whsec_` → `v1` HMAC, `whpk_` → `v2a` Ed25519; `endpoint-id` required for `v2a`). `types` is an optional per-endpoint allow-list; omit it to accept any known `@WebhookType`. See [SPEC §8a](./docs/reference/SPEC.md#8a-secret-source--rotation-secretprovider) and [§8e](./docs/reference/SPEC.md#8e-asymmetric-verification--ed25519-v2a-whpk_). |
| `webhooks.consumer.max-body-size` | `256KB` | Bodies larger than this are rejected with `413` before verification. See [SPEC §14a](./docs/reference/SPEC.md#14a-production-hardening). |
| `webhooks.consumer.in-memory-idempotency-max-size` | `100000` | Maximum entries in the in-memory deduplication cache (applies only when idempotency is enabled). See [SPEC §11](./docs/reference/SPEC.md#11-idempotency-without-storage). |
| `webhooks.consumer.handler.timeout` | `30s` | Per-handler timeout in `async` mode. See [SPEC §15](./docs/reference/SPEC.md#15-sync-vs-async-handling). |
| `webhooks.consumer.handler.core-pool-size` | `4` | Core pool size in `async` mode. |
| `webhooks.consumer.handler.max-pool-size` | `16` | Maximum pool size in `async` mode. |
| `webhooks.consumer.handler.queue-capacity` | `1000` | Bounded work-queue capacity in `async` mode. |
| `webhooks.consumer.replay-tolerance` | `5m` | Allowed clock skew for the replay window when it is enabled; a request is accepted only if `\|now − timestamp\| ≤ tolerance`. Ignored when the window is disabled. See [SPEC §8b](./docs/reference/SPEC.md#8b-replay-window-timestamp-freshness). |
| `webhooks.producer.destinations.<name>` | — | A named destination `{ url, secret?, endpoint-id?, additional-secrets? }` referenced by `@WebhookClient(destination = "<name>")` (`whsec_` → `v1` HMAC, `whsk_` → `v2a` Ed25519; `endpoint-id` required for `v2a`). See [Secret rotation](#secret-rotation) and [SPEC §19i](./docs/reference/SPEC.md#19i-asymmetric-signing--ed25519-v2a-whsk_). |
| `webhooks.producer.retry.max-attempts` | `3` | Total delivery attempts including the first; `1` disables retry. Values `< 1` are clamped to `1`. See [SPEC §19b](./docs/reference/SPEC.md#19b-in-process-retry-no-persistence). |
| `webhooks.producer.retry.initial-backoff` | `500ms` | Wait before the second attempt; doubles each further attempt up to `max-backoff`. |
| `webhooks.producer.retry.max-backoff` | `5s` | Ceiling on any single backoff. |
| `webhooks.producer.retry.jitter` | `0.5` | Fraction (`0.0`–`1.0`) of each backoff randomised downward, so retries do not synchronise. |
| `webhooks.producer.http.connect-timeout` | `5s` | Maximum time to establish the TCP connection to a destination. See [SPEC §19c](./docs/reference/SPEC.md#19c-http-timeouts). |
| `webhooks.producer.http.read-timeout` | `10s` | Maximum time to wait for the response once connected. A timeout is a transport error and is retryable. |
| `webhooks.producer.security.allowed-hosts` | `[]` (no restriction) | When non-empty, only these hostnames may be delivered to (exact, case-insensitive; subdomains not implied). See [SPEC §19d](./docs/reference/SPEC.md#19d-destination-url-guard-ssrf). |

### Replaceable beans

Every library bean is declared with `@ConditionalOnMissingBean`. Define a bean of the same type to
override the default; no component scan is involved and nothing needs to be disabled first.

| Bean | Default | Override to |
| --- | --- | --- |
| `SecretProvider<Endpoint>` (consumer) | Configuration-based | Resolve consumer secrets from a secrets manager. See [SPEC §8a](./docs/reference/SPEC.md#8a-secret-source--rotation-secretprovider). |
| `SecretProvider<Destination>` (producer) | Configuration-based | Resolve producer secrets from a secrets manager. Registered and injected by the bean name `destinationSecretProvider` (so it never collides with the consumer's `SecretProvider<Endpoint>`); override with `@Bean("destinationSecretProvider")`. |
| `HandlerExecutor` (consumer) | `Sync` or `Async` per `handler.mode` | Provide a custom dispatch strategy. See [Async handler execution](#async-handler-execution) and [SPEC §15](./docs/reference/SPEC.md#15-sync-vs-async-handling). |
| `IdempotencyGuard` (consumer) | No-op or in-memory | Use Redis or another shared store. See [SPEC §11](./docs/reference/SPEC.md#11-idempotency-without-storage). |
| `TimestampValidator` (consumer) | No-op or clock-skew per `replay-window-enabled` | Implement a custom timestamp-freshness policy. See [SPEC §8b](./docs/reference/SPEC.md#8b-replay-window-timestamp-freshness). |
| `WebhookVerifier` (consumer) | `DefaultWebhookVerifier` (`v1` + `v2a`), or no-op per `signature-verification-enabled` | Change the verification scheme (e.g. JWS). See [SPEC §8e](./docs/reference/SPEC.md#8e-asymmetric-verification--ed25519-v2a-whpk_) and [§8f](./docs/reference/SPEC.md#8f-disabling-message-security). |
| `WebhookTransport` (producer) | `RetryingWebhookTransport` → `InterceptingWebhookTransport` → `RestClientWebhookTransport` | Send asynchronously, through a queue, or with a different delivery/retry policy. To add **transport auth**, publish a `WebhookRequestInterceptor` instead so retry + the SSRF guard remain. See [SPEC §19](./docs/reference/SPEC.md#19-producer--annotation-driven-sending). |
| `WebhookRequestInterceptor` (producer, 0..n) | None | Add outbound request headers — an OAuth2 `Authorization: Bearer` token (transport auth), tenancy, tracing — inside the transport chain. See [Transport authentication](./docs/guides/transport-authentication.md). |
| `WebhookSigner` (producer) | `DefaultWebhookSigner` (`v1` + `v2a`), or no-op per `signing-enabled` | Change the signing scheme. See [SPEC §19h](./docs/reference/SPEC.md#19h-disabling-message-signing) and [§19i](./docs/reference/SPEC.md#19i-asymmetric-signing--ed25519-v2a-whsk_). |
| `DestinationUrlValidator` (producer) | Allow-all, or host allow-list per `security.allowed-hosts` | Implement a custom outbound-URL policy (CIDR ranges, private-IP / DNS-rebind blocking). See [SPEC §19d](./docs/reference/SPEC.md#19d-destination-url-guard-ssrf). |
| `WebhookDeliveryListener` (producer) | No-op | Capture an abandoned outbound delivery (fatal `4xx` or retries exhausted) in a dead-letter store or alert. See [SPEC §20.5](./docs/reference/SPEC.md#205-failure-hooks-dead-letter-capture). |
| `HandlerFailureListener` (consumer) | No-op | Capture an `async` handler that threw or timed out (the `204` was already sent). See [SPEC §20.5](./docs/reference/SPEC.md#205-failure-hooks-dead-letter-capture). |
| `WebhookMetrics` (consumer) | `MicrometerWebhookMetrics` (if `MeterRegistry` present) or no-op | Emit receive/lag/failure metrics to a custom telemetry backend. |
| `MetadataContributor` (consumer, additive) | None | Copy vendor headers into `meta.attribute(...)`; any number may be registered. |

### Observability

Instrumentation uses the standard Micrometer Observation API (`@Observed` with `ObservedAspect`), so
no custom flag is introduced; observations are controlled exactly as any other Spring observation.

> **Requires Spring AOP.** `@Observed` is advised by Micrometer's `ObservedAspect`, which needs Spring
> AOP/AspectJ on the classpath. Both libraries declare `spring-boot-starter-aop` as **optional**, so the
> `webhook.receive` / `webhook.send` timers and spans appear only when the host application also has AOP
> available (add `spring-boot-starter-aop` if it does not). Without AOP the libraries still function
> fully — they simply emit no `@Observed` timing for these two methods. Actuator alone (a `MeterRegistry`)
> is **not** sufficient; AOP is what makes the annotated method observed.

Each operation produces a single observation that yields both a metric (a timer) and a tracing span:

| Operation | Observation | Metric (timer) | Span | Instrumented method |
| --- | --- | --- | --- | --- |
| Receive | `webhook.receive` | `webhook.receive` | `webhook receive` | `WebhookDispatcher#dispatch` (consumer) |
| Send | `webhook.send` | `webhook.send` | `webhook send` | `WebhookPublisher#publish` (producer) |

Each timer exposes the standard Micrometer timer series. For example, through
`micrometer-registry-prometheus` the `webhook.receive` timer is reported as:

| Series | Meaning |
| --- | --- |
| `webhook_receive_seconds_count` | Number of dispatched inbound webhooks. |
| `webhook_receive_seconds_sum` | Total time spent dispatching. |
| `webhook_receive_seconds_max` | Maximum observed dispatch duration. |

The `webhook_send_seconds_*` family is reported identically for the producer. Timers also carry an
`error` tag (`true` or `false`) recorded by the observation, so successful and failed operations can be
separated. The inbound HTTP layer is covered independently by actuator's own `http.server.requests`
metric; the observations above measure the dispatch/publish step specifically — the `webhook.receive`
timer wraps `WebhookDispatcher#dispatch` (the routing + handler invocation), not the preceding verify and
deserialize stages, and `webhook.send` wraps `WebhookPublisher#publish` (build → sign → send). No
additional custom tags (such as event `type` or `destination`) are applied.

Where the data is reported depends entirely on what the host application wires. Add
`spring-boot-starter-actuator` with a registry (for example `micrometer-registry-prometheus`) for
metrics, and a tracer bridge (OpenTelemetry or Brave) for spans. With neither present, instrumentation
falls back to `ObservationRegistry.NOOP`, producing no output and requiring no configuration.

To disable individual observations:

```yaml
management:
  observations:
    enable:
      webhook.receive: false   # disables consumer receive metrics and spans
      webhook.send: false      # disables producer send metrics and spans
# To disable all observations in the application:
# management.observations.enable: false
```

See [SPEC §13](./docs/reference/SPEC.md#13-observability) for the full design.

#### Correlation, lag logging, and opt-in metrics

On top of the observation timers above, the consumer adds correlation, lag logging, and opt-in counters
that align the toolkit with [Standard Webhooks §11](./docs/reference/STANDARD_WEBHOOKS.md#11-tracing--logging-consumer-side)
without taking on any storage (see [SPEC §20.6](./docs/reference/SPEC.md#206-spec-alignment-backlog)).

**`webhook-id` correlation (A3, app-owned).** The stable `webhook-id` — the id the sender quotes in
support tickets, constant across its retries — is surfaced on `WebhookMetadata.webhookId()` for every
handler. The toolkit writes **nothing** to the SLF4J `MDC`: correlation is the application's concern (see
[SPEC §9b](./docs/reference/SPEC.md#9b-correlation--trace-context-app-owned)). Bind it in a servlet filter
that runs before the webhook controller and clear it in a `finally`, then add it to your log pattern so
every line — including a verification-failure line — carries it (a runnable filter lives in the sample
consumer app):

```
logging.pattern.level: "%5p [webhook.id=%X{webhook.id}]"
```

**Delivery-lag / event-lag logging (A2).** Once a delivery is verified, the controller logs a single line
reporting two lags computed from data already in hand:

| Lag | Formula | Meaning |
| --- | --- | --- |
| `deliveryLag` | `now − webhook-timestamp` | Age of this delivery attempt; grows with the sender's retries. |
| `eventLag` | `now − body.timestamp` | Age of the underlying event; `n/a` when the body carried no timestamp. |

```
INFO  [webhook.id=msg_2abc] Webhook verified type=contact.created deliveryLag=84ms eventLag=1203ms
```

**Opt-in metrics (A4).** When the host provides a Micrometer `MeterRegistry` (e.g. via
`spring-boot-starter-actuator`), the toolkit emits the meters below; with no registry it emits nothing
(a no-op `WebhookMetrics` bean) and the receive path stays zero-cost. Override with a `@Bean WebhookMetrics`
to target any backend.

| Operation on the receive path | Meter | Type | Tags | Example value |
| --- | --- | --- | --- | --- |
| A verified webhook accepted for dispatch | `webhook.received` | counter | `type` | `webhook_received_total{type="contact.created"} 42` |
| Verification failed | `webhook.verification.failed` | counter | `reason` | `..._total{reason="signature"} 3` |
| A handler ran (success or failure) | `webhook.handle.duration` | timer | `type` | `..._seconds_max{type="invoice.paid"} 0.031` |
| Delivery lag observed | `webhook.delivery.lag` | distribution summary (ms) | — | `..._max 84.0` |
| Event lag observed | `webhook.event.lag` | distribution summary (ms) | — | `..._max 1203.0` |

The `reason` tag is drawn from a fixed, low-cardinality vocabulary, so the registry never sees unbounded
tag values:

| `reason` | Cause |
| --- | --- |
| `missing-headers` | A required `webhook-id` / `webhook-timestamp` / `webhook-signature` header was absent. |
| `timestamp` | The `webhook-timestamp` header was non-numeric or outside the replay window. |
| `no-secret` | No secret is configured for the matched endpoint. |
| `signature` | The signature matched no candidate secret. |
| `other` | Any other verification failure. |

All three features are demonstrated end-to-end by the sample apps under `webhooks-sample/`, which POST genuine
signed webhooks at the live endpoint and surface the app-bound correlation id in the `MDC`, the verified log
line, and the meters against a real `MeterRegistry`.

> For a full walkthrough with **diagrams** and worked request→log/metric examples, see the
> [Observability guide](./docs/guides/observability.md).

### HTTP status codes

| Outcome | Status |
| --- | --- |
| Delivered — handler ran (`sync`) or accepted onto the pool (`async`), duplicate, or ack-and-ignore | `204 No Content` |
| Missing or invalid signature, invalid timestamp, or missing headers | `400 Bad Request` |
| Payload validation failed (or unknown JSON field) | `400 Bad Request` |
| Malformed payload — body not a valid envelope, or its `data` fails strict deserialization | `400 Bad Request` |
| Unknown type under the `reject` policy | `400 Bad Request` |
| Body exceeds `max-body-size` | `413 Payload Too Large` |
| A modelled, accepted type has no matching `@WebhookHandler` | `500 Internal Server Error` |
| No endpoint configured for the matched path | `500 Internal Server Error` |

Errors are returned as RFC 7807 `ProblemDetail` responses.

A known event type that reaches dispatch with **no registered handler** is a configuration gap, so it
returns `500` (the sender keeps retrying while you wire the handler) rather than silently acking it as
"processed". Only types the toolkit does **not** model are ack-and-ignored (the `unknown-type-policy`).

## Replay window

Standard Webhooks signs each delivery over `msg_id.timestamp.payload` and sends the timestamp in the
`webhook-timestamp` header. Comparing that timestamp against the server clock — the **replay window** —
is what lets a stateless consumer reject a stale, replayed delivery: a signature alone proves
authenticity but not freshness, so without the window an attacker who records one valid request can
re-send it indefinitely.

> **Secure by default.** Following the Standard Webhooks specification, the replay window is **enabled by
> default** with a `5m` tolerance. Combine it with an `IdempotencyGuard` or idempotent handlers for
> defense in depth against retries within the window.

Tune the tolerance (or opt out if deliveries are intentionally delayed):

```yaml
webhooks:
  consumer:
    replay-window-enabled: true   # default: true
    replay-tolerance: 5m          # allowed clock skew in either direction (default: 5m)
```

When enabled, a request is accepted only if `|now − timestamp| ≤ replay-tolerance` and rejected with
`400 Bad Request` otherwise. The upstream `com.standardwebhooks` library hard-codes a fixed ±5-minute
window with no way to tune or disable it, so the toolkit performs the timestamp check itself through a
`TimestampValidator` bean — a no-op validator when the window is off, a clock-skew validator when it is
on. Supply your own `@Bean TimestampValidator` for a custom freshness policy. See
[SPEC §8b](./docs/reference/SPEC.md#8b-replay-window-timestamp-freshness) for the full design.

## Secret rotation

Both sides support zero-downtime (blue/green) secret rotation through an `additional-secrets` list, so
a secret can be rolled out across sender and receiver in any order with no dropped deliveries.

On the **consumer**, a signature made with the primary secret or any additional secret is accepted:

```yaml
webhooks:
  consumer:
    endpoints:
      - path: /webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}            # primary (new)
        additional-secrets:
          - ${CRM_WEBHOOK_SECRET_PREVIOUS}        # accepted during rotation
```

On the **producer**, declaring additional secrets enables dual-signing: the same payload is signed with
the primary secret and every additional secret, and all signatures are sent in one space-separated
`webhook-signature` header. A receiver that trusts either secret verifies the delivery successfully.

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

See [SPEC §8a](./docs/reference/SPEC.md#8a-secret-source--rotation-secretprovider) and
[SPEC §19a](./docs/reference/SPEC.md#19a-secret-rotation-bluegreen) for the full procedure.

## Producer retry

A webhook delivery is a network call to a third party, so transient failures are expected. The producer
retries them **in process** — no queue, disk, or scheduler, so the no-storage rule still holds. Retry is
**on by default** (three attempts) and lives in a transport decorator, so the base transport stays
retry-free and either layer is independently replaceable.

Only failures that might succeed on a repeat are retried: a `5xx`, a `429 Too Many Requests`, or a
transport error (timeout, connection refused). Any other `4xx` (for example `400`, `401`, `404`) is
treated as fatal and fails immediately, as is a URL rejected by the [SSRF guard](#configuration-reference).
Backoff is exponential (`initial · 2^(attempt-1)`, capped at `max-backoff`) with jitter so retries do not
synchronise into storms; the wait is a bounded `Thread.sleep` on the calling thread. After the attempts
are exhausted, the last `WebhookDeliveryException` is rethrown to the caller.

```yaml
webhooks:
  producer:
    retry:
      max-attempts: 3            # total attempts incl. the first; 1 disables retry
      initial-backoff: 500ms     # before the 2nd attempt; doubles each further attempt up to max-backoff
      max-backoff: 5s            # ceiling on any single backoff
      jitter: 0.5                # 0.0–1.0; fraction of each backoff randomised downward
    http:
      connect-timeout: 5s        # max time to establish the TCP connection
      read-timeout: 10s          # max time to wait for the response once connected
    security:
      allowed-hosts: []          # empty = no restriction; list hosts to enforce an SSRF allow-list
```

**Honoring `Retry-After`.** When a retryable response (`429`/`503`) carries a `Retry-After` header, the
producer uses it as a **backoff floor**: the next wait is `max(exponential-backoff, Retry-After)`, with the
`Retry-After` itself clamped to `max-backoff` so an over-eager server can never push a single wait past the
configured ceiling. Both header forms are parsed — delta-seconds (`Retry-After: 120`) and an HTTP-date
(`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`); a negative or past value clamps to zero, and an
unparseable one is ignored. This closes [SPEC §20.6/A1](./docs/reference/SPEC.md#206-spec-alignment-backlog).

| Server response (attempt 1, `initial-backoff: 500ms`, `max-backoff: 5s`) | Computed exponential | Effective wait |
| --- | --- | --- |
| `503`, no `Retry-After` | 500ms | **500ms** (exponential) |
| `503 Retry-After: 2` (2s) | 500ms | **2s** (Retry-After is larger) |
| `429 Retry-After: 0` | 500ms | **500ms** (floor below exponential, ignored) |
| `503 Retry-After: 3600` (1h) | 500ms | **5s** (clamped to `max-backoff`) |

This is demonstrated end-to-end by the sample apps under `webhooks-sample/`, which drive the real transport and
surface the parsed delay on `WebhookDeliveryException.getRetryAfter()`.
See the [Reliability guide → Honoring Retry-After](./docs/guides/reliability.md#honoring-retry-after)
for a sequence diagram and the full effective-wait table.

For durable, cross-restart retry, plug a queue in behind the `WebhookTransport` bean. See
[SPEC §19b](./docs/reference/SPEC.md#19b-in-process-retry-no-persistence), [§19c](./docs/reference/SPEC.md#19c-http-timeouts), and
[§19d](./docs/reference/SPEC.md#19d-destination-url-guard-ssrf).

## Async handler execution

On the consumer side, handler execution is a swappable strategy behind the `HandlerExecutor` SPI. The
default is **sync**; switch to async with a single flag — no code change:

```yaml
webhooks:
  consumer:
    handler:
      mode: async            # sync (default) | async
      core-pool-size: 4      # async-only tuning (defaults shown)
      max-pool-size: 16
      queue-capacity: 1000   # a full queue applies CallerRunsPolicy back-pressure
      timeout: 30s           # per-handler; a handler exceeding it is interrupted and logged
```

The two modes differ in **what a `2xx` means**:

| | `sync` (default) | `async` |
| --- | --- | --- |
| Where the handler runs | Inline, on the request thread | Off the request thread, on a bounded pool |
| Meaning of the `2xx` | **Processed** — returned after the handler completes | **Accepted** (`204`) — returned immediately |
| A handler that throws | Mapped to the HTTP status the sender sees | Sender already got `204`; the failure is only logged |
| Crash safety | Nothing in flight to lose; the sender's retry is the back-pressure | A crash before a queued task runs **drops that delivery** |

- **Prefer `sync`** when handlers finish comfortably inside the sender's response window (typically
  15–30s). A `2xx` then honestly means "done", and the sender's own retries cover failures — no in-JVM
  queue to lose on restart, and no storage.
- **Use `async`** only when handlers are slow *and* the sender's timeout is tight, *and* each handler is
  best-effort or idempotent (an async delivery can be dropped on crash). The pool is bounded (core/max
  threads + work queue), runs **daemon** threads, and a single scheduler arms a one-shot cancel after
  `timeout` so a stuck handler cannot tie up a pool thread forever. Misconfiguration is rejected at
  **startup**, not on the first request: `core-pool-size` must not exceed `max-pool-size`, `timeout` must
  be positive, and the body/idempotency caps must be positive (see [SPEC §14a](./docs/reference/SPEC.md#14a-production-hardening)).
- **For slow work, the usual pattern is to stay in `sync`** and push the expensive part (third-party
  calls, heavy writes) to your application's own async infrastructure *inside* the handler, after it has
  extracted what it needs from the event — keeping delivery acknowledgement honest.

> **`async` does not preserve event order.** Handlers run concurrently on the pool (and inline on the
> request thread under back-pressure), so events sent A → B can complete B-first. If a handler must see a
> related series in order (e.g. `created` before `updated` for the same entity), use `sync` mode or make
> the handler order-tolerant (reconcile against a version/timestamp, or serialise per key yourself). The
> toolkit holds no state and cannot sequence events for you. See [SPEC §15](./docs/reference/SPEC.md#15-sync-vs-async-handling).

To replace the strategy entirely (for example virtual threads, or bridging to a queue), define your own
`HandlerExecutor` bean; it is `@ConditionalOnMissingBean`. See
[SPEC §15](./docs/reference/SPEC.md#15-sync-vs-async-handling).

## Failure hooks (dead-letter capture)

The toolkit stores nothing, but it never lets a failure vanish silently. Two no-op-by-default listener
SPIs give you one place to capture a failure — persist it to a dead-letter store, raise an alert, or
enqueue it for replay — without the core taking on any state. Both are `@ConditionalOnMissingBean`, so
publishing your own `@Bean` replaces the no-op.

**Producer — `WebhookDeliveryListener`** fires once when an outbound delivery is abandoned (a fatal `4xx`,
or retries exhausted), just before the `WebhookDeliveryException` is rethrown. It receives a
`DeliveryFailure` with the send-ready signed request, the attempt count, and the last cause — enough to
persist and replay later:

```java
@Bean
WebhookDeliveryListener deadLetter(MyDeadLetterStore store) {
    return failure -> store.save(failure.url(), failure.headers(), failure.body(),
                                 failure.attempts(), failure.cause());
}
```

**Consumer — `HandlerFailureListener`** surfaces `async` handler failures that the `204` already hid. In
`sync` mode a thrown handler becomes the non-2xx the sender retries on, so there is nothing to report; in
`async` mode the executor logs the failure at `ERROR` **and** routes it here — `onHandlerFailed(type,
cause)` for a thrown exception, `onHandlerTimeout(type)` when the per-handler timeout interrupted it.

Both hooks are isolated: a listener that itself throws is caught and logged and **cannot** change the
outcome (the producer still rethrows; the async executor still moves on) — a down dead-letter sink never
turns one failure into two. See [SPEC §20.5](./docs/reference/SPEC.md#205-failure-hooks-dead-letter-capture).

| Operation | Hook fired | `DeliveryFailure` fields |
| --- | --- | --- |
| Destination returns a fatal `4xx` (e.g. `404`) | `onDeliveryFailed` once | `reason = FATAL`, `attempts = 1` (never retried) |
| Destination keeps returning `503` until `max-attempts` spent | `onDeliveryFailed` once | `reason = EXHAUSTED`, `attempts = max-attempts` |
| `async` handler throws | `onHandlerFailed(type, cause)` | — (after the sender already got `204`) |
| `async` handler exceeds `timeout` | `onHandlerTimeout(type)` | — |

The producer hook is demonstrated end-to-end by the sample apps under `webhooks-sample/`, which drive the real
transport chain against a destination and surface the captured `DeliveryFailure`. See the
[Reliability guide → Dead-letter failure hooks](./docs/guides/reliability.md#dead-letter-failure-hooks)
for the abandon-path diagram.

## Running the samples

The runnable demos are two standalone Spring Boot apps under `webhooks-sample/` (outside the library reactor),
each depending on the published starters.

```bash
# from the repo root: build and quality-gate the libraries, installing the starters locally
mvn -pl webhooks-consumer-starter,webhooks-producer-starter -am install

# from webhooks-sample/: run each side
mvn -pl consumer-sample spring-boot:run   # receives webhooks on port 9091
mvn -pl producer-sample spring-boot:run   # sends webhooks on port 9090

# from webhooks-sample/: bring both up together and run the cross-library integration test
docker compose up --build
./integration-test.sh
```

- **`consumer-sample` (port 9091):** a multi-type CRM endpoint (`/webhooks/crm`) and a single-type
  billing endpoint (`/webhooks/billing/invoice-paid`) verified with the demo HMAC secret
  `whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw` (`v1`), plus an asymmetric `/webhooks/crm-v2a` endpoint verified
  with an Ed25519 public key (`v2a`). It demonstrates `@Redact` masking in the handler logs.
- **`producer-sample` (port 9090):** two `@WebhookClient` interfaces registered via
  `@EnableWebhookClients` — `CrmWebhooks` (`v1`, exposed through `POST /publish/contact-created`) and
  `CrmWebhooksV2a` (`v2a`, exposed through `POST /publish/contact-created-v2a`) — each signing and POSTing a
  real Standard Webhooks request to the matching consumer endpoint.

The Docker-based `webhooks-sample/integration-test.sh` brings up both apps, publishes via the producer, and
asserts the consumer handled the webhook — a complete cross-library round trip over real HTTP.

## Quality gate

Every library module enforces 100% JaCoCo line and branch coverage and a 100% PITest mutation score. The
coverage gate runs on every `mvn verify`; mutation testing is slower, so it runs with the `mutation` profile
(`mvn verify -Pmutation`), which CI enables on every push and pull request. Either gate fails the build if
quality regresses. The enforced
thresholds and the sole executable-code-free `annotations/**` exclusion live in each module's `pom.xml`;
the gate is described in [SPEC §16](./docs/reference/SPEC.md#16-quality-gate--coverage--mutation-testing).
The authoritative, always-current counts are the JaCoCo and PITest reports produced by
`mvn clean verify -Pmutation` under each module's `target/`.

The standalone sample apps under `webhooks-sample/` (`producer-sample`, `consumer-sample`) are not part of the
gated library reactor, so the 100% gate is not enforced on them; their cross-library behaviour is
exercised instead by the Docker-based `webhooks-sample/integration-test.sh`.

Beyond coverage and mutation, the build can scan dependencies for known CVEs with
[OWASP dependency-check](https://owasp.org/www-project-dependency-check/), run via the opt-in
`security-scan` profile so the fast gate stays offline:

```bash
NVD_API_KEY=<key> mvn -Psecurity-scan verify   # an NVD API key avoids download throttling
```

It fails the build on a high/critical finding (CVSS ≥ 7). See [SPEC §19e](./docs/reference/SPEC.md#19e-dependency-vulnerability-scanning).

## Further reading

- **[`docs/`](./docs/README.md) — the full documentation site** (getting started, architecture, consumer/producer
  guides, security, reliability, observability, configuration & API reference, runbook, FAQ) with diagrams
  throughout. Start here.
- [`SPEC.md`](./docs/reference/SPEC.md) — full design specification, including the
  [producer](./docs/reference/SPEC.md#19-producer--annotation-driven-sending).
- The spec-alignment features (Retry-After, lag logging, `webhook-id` correlation, opt-in metrics,
  dead-letter hooks) with diagrams and worked examples are covered in the
  [Reliability](./docs/guides/reliability.md) and [Observability](./docs/guides/observability.md) guides.
- [`STANDARD_WEBHOOKS.md`](./docs/reference/STANDARD_WEBHOOKS.md) — the protocol reference (headers, signature scheme,
  payloads).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](./CONTRIBUTING.md) and the [Code of Conduct](./CODE_OF_CONDUCT.md).
Please report security issues privately as described in [SECURITY.md](./SECURITY.md). Release notes are in
[CHANGELOG.md](./CHANGELOG.md).

## License

Copyright 2026 Mradul Pandey.

Licensed under the [Apache License, Version 2.0](./LICENSE). You may not use this project except in
compliance with the License. Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
express or implied.
