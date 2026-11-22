# Configuration reference

Every `webhooks.*` property, with its type and default. The consumer binds `webhooks.consumer.*`; the
producer binds `webhooks.producer.*`. The two are independent — configure only the side(s) you depend on.

> Durations accept Spring's syntax (`500ms`, `5s`, `5m`). Sizes accept `DataSize` syntax (`256KB`, `1MB`).
> `${ENV}` placeholders resolve from the environment — keep secrets out of committed config.

---

## Consumer (`webhooks.consumer`)

### Top-level

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `@WebhookType` discovery | — | *(auto)* | Discovered automatically by scanning the host app's `@SpringBootApplication` package(s); override with a custom `@Bean WebhookTypeRegistry`. |
| `unknown-type-policy` | `ack-and-ignore` \| `reject` | `ack-and-ignore` | What to do when a `type` has no matching `@WebhookType` class, or is outside an endpoint's allow-list. `ack-and-ignore` returns `204`; `reject` returns `400`. |
| `in-memory-idempotency` | boolean | `false` | Best-effort, per-instance in-memory dedupe on `webhook-id`. Lossy on restart. |
| `in-memory-idempotency-max-size` | int | `100000` | Hard cap on retained ids (size-capped LRU); the oldest are evicted when full, so a flood of unique ids can't exhaust memory. |
| `max-body-size` | DataSize | `256KB` | Inbound body ceiling. A larger body is rejected `413` **before** verification, bounding memory/thread use. |
| `signature-verification-enabled` | boolean | `true` | Verify the `webhook-signature` of every inbound request. **On by default.** Set `false` to accept all requests unsigned (wires the no-op verifier, needs no endpoint secret) — only when the transport is authenticated instead. Logs a prominent WARN at every startup while off. See [Verification → disabling verification](../guides/verification-and-security.md#disabling-verification). |
| `validate-secrets-on-startup` | boolean | `true` | Validate every endpoint secret at boot; a missing/blank/invalid secret then fails fast instead of per-request `500`s. Skipped when `signature-verification-enabled=false` (no secret needed). |
| `replay-window-enabled` | boolean | `false` | Enable timestamp-freshness checking. **Off by default** — enable for internet-facing endpoints. |
| `replay-tolerance` | Duration | `5m` | Allowed clock skew when the replay window is on: accept only if `|now − webhook-timestamp| ≤ tolerance`. Ignored when disabled. |
| `endpoints` | List&lt;Endpoint&gt; | *(empty)* | The configured receive paths (see [Endpoints](#endpoints-webhooksconsumerendpoints) below). Configure at least one to receive traffic. |

> **Payload validation has no config.** It is declared with Bean Validation annotations on the `@WebhookType`
> event classes (`@NotBlank`, `@Positive`, `@Size`, …) and enforced on every deserialized event; unknown
> fields inside the event `data` object are rejected by the strict event reader. See
> [configuration §6a](./SPEC.md#6a-per-type-payload-validation).

### Handler execution (`webhooks.consumer.handler`)

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `mode` | `sync` \| `async` | `sync` | `sync` runs the handler inline (`2xx` = processed); `async` returns `204` first and runs it on a pool (`2xx` = accepted). See [Reliability → sync vs async](../guides/reliability.md#sync-vs-async-handling). |
| `core-pool-size` | int | `4` | Async core threads. |
| `max-pool-size` | int | `16` | Async max threads. |
| `queue-capacity` | int | `1000` | Async bounded queue; a full queue applies back-pressure via `CallerRunsPolicy` (runs on the request thread). |
| `timeout` | Duration | `30s` | Async per-handler timeout; an over-running handler is interrupted and routed to `onHandlerTimeout`. |

### Correlation & trace context

There is **no tracing configuration** on the toolkit: it writes nothing to the SLF4J `MDC`, and
correlation is the application's concern (see [SPEC §9b](./SPEC.md#9b-correlation--trace-context-app-owned)).
The stable `webhook-id` is always available via `WebhookMetadata.webhookId()`, and the toolkit propagates
whatever your application puts in the `MDC` across the async handler hop. To correlate a producer→consumer
delivery, agree on a header name out of band: the producer adds it with a `WebhookRequestInterceptor` and
the consumer reads it into the `MDC` in its own servlet filter. A full, runnable example lives in the
sample apps; see also [Observability → tracing](../guides/observability.md#tracing--observations).

### Endpoints (`webhooks.consumer.endpoints[]`)

A list; each entry is one listen path.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | String | *(required)* | Listen path, e.g. `/webhooks/crm`. |
| `secret` | String | *(required)* | Primary verification key: a `whsec_…` HMAC secret (`v1`) **or** a `whpk_…` Ed25519 public key (`v2a`). The scheme is selected by prefix. |
| `additional-secrets` | List&lt;String&gt; | *(empty)* | Extra accepted keys for zero-downtime rotation (or a mixed `v1`/`v2a` migration); a signature verifying against the primary **or** any of these is accepted. See [secret rotation](../guides/verification-and-security.md#secret-rotation). |
| `endpoint-id` | String | *(none)* | **Required for `v2a`** (when `secret`/`additional-secrets` holds a `whpk_` key): the stable identifier prepended to the signed content. Must match the producer's value exactly and **must not contain `.`**. Ignored (with a startup warning) on a `v1`-only endpoint. See [asymmetric signatures](../guides/verification-and-security.md#asymmetric-signatures-ed25519-v2a). |
| `types` | List&lt;String&gt; | *(empty)* | Optional allow-list of accepted event types. Empty = accept any known `@WebhookType`. A type outside the list is handled by `unknown-type-policy`. |

### Consumer example

```yaml
webhooks:
  consumer:
    unknown-type-policy: ack-and-ignore
    replay-window-enabled: true
    replay-tolerance: 5m
    max-body-size: 256KB
    handler:
      mode: sync
    # Payload validation is declared on the @WebhookType classes (Bean Validation), not here.
    endpoints:
      - path: /webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}
        additional-secrets:
          - ${CRM_WEBHOOK_SECRET_OLD}
        types: [contact.created, contact.updated]
      - path: /webhooks/billing
        secret: ${BILLING_WEBHOOK_SECRET}
      - path: /webhooks/partner                # asymmetric v2a (Ed25519)
        endpoint-id: ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb
        secret: ${PARTNER_WEBHOOK_PUBLIC_KEY}   # whpk_… the partner's public key; you never hold their private key
```

---

## Producer (`webhooks.producer`)

### Top-level

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `destinations` | Map&lt;String, Destination&gt; | *(empty)* | Named outbound destinations keyed by `@WebhookClient(destination = "...")` (see [Destinations](#destinations-webhooksproducerdestinationsname) below). |
| `signing-enabled` | boolean | `true` | Sign every outbound delivery with a `webhook-signature` header. **On by default.** Set `false` to send unsigned (wires the no-op signer, needs no destination secret) — only when the receiver authenticates the transport instead. See [Verification → disabling signing](../guides/verification-and-security.md#disabling-signing). |
| `validate-destinations-on-startup` | boolean | `true` | Validate every destination URL and signing secret at boot; a problem then fails fast instead of surfacing on the first send. |

### Destinations (`webhooks.producer.destinations.<name>`)

A map keyed by the name used in `@WebhookClient(destination = "...")`.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `url` | String | *(required)* | Absolute URL the signed envelope is POSTed to. |
| `secret` | String | *(required)* | Primary signing key: a `whsec_…` HMAC secret (`v1`) **or** a `whsk_…` Ed25519 private key (`v2a`). The scheme is selected by prefix. Not required when `signing-enabled=false`. |
| `additional-secrets` | List&lt;String&gt; | *(empty)* | Extra signing keys for zero-downtime rotation (or a mixed `v1`/`v2a` migration); the producer **dual-signs** with the primary and each of these, sending all signatures in one space-separated `webhook-signature` header. See [secret rotation](../guides/verification-and-security.md#secret-rotation). |
| `endpoint-id` | String | *(none)* | **Required for `v2a`** (when `secret`/`additional-secrets` holds a `whsk_` key): the stable identifier prepended to the signed content. Must match the consumer's value exactly and **must not contain `.`**. Ignored (with a startup warning) on a `v1`-only destination. See [asymmetric signatures](../guides/verification-and-security.md#asymmetric-signatures-ed25519-v2a). |

### HTTP (`webhooks.producer.http`)

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `connect-timeout` | Duration | `5s` | Max time to establish the TCP connection. |
| `read-timeout` | Duration | `10s` | Max time to wait for the response once connected. A timeout is retryable. |

### Retry (`webhooks.producer.retry`)

In-process, in-memory, bounded — no queue, no disk. See [Reliability → retry](../guides/reliability.md#producer-retry--backoff).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `max-attempts` | int | `3` | Total attempts incl. the first; `1` disables retry. Values `< 1` are treated as `1`. |
| `initial-backoff` | Duration | `500ms` | Wait before the 2nd attempt; doubles each further attempt up to `max-backoff`. |
| `max-backoff` | Duration | `5s` | Ceiling on any single backoff (also the `Retry-After` clamp). |
| `jitter` | double | `0.5` | 0.0–1.0; each wait is sampled from `[backoff·(1−jitter), backoff]` to avoid retry storms. |

### Security (`webhooks.producer.security`)

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `allowed-hosts` | List&lt;String&gt; | *(empty)* | SSRF allow-list of destination hostnames (exact, case-insensitive). Empty = no restriction. A blocked host fails fast before any request. See [SSRF guard](../guides/verification-and-security.md#destination-url-guard-ssrf). |

### Producer example

```yaml
webhooks:
  producer:
    destinations:
      crm:
        url: https://partner.example.com/webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}
        additional-secrets:
          - ${CRM_WEBHOOK_SECRET_OLD}
      billing:
        url: https://billing.example.com/webhooks
        secret: ${BILLING_WEBHOOK_SECRET}
      partner:                                 # asymmetric v2a (Ed25519)
        url: https://partner.example.com/webhooks
        endpoint-id: ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb
        secret: ${PARTNER_WEBHOOK_PRIVATE_KEY} # whsk_… private key; the partner holds only the whpk_ public key
    http:
      connect-timeout: 5s
      read-timeout: 10s
    retry:
      max-attempts: 3
      initial-backoff: 500ms
      max-backoff: 5s
      jitter: 0.5
    security:
      allowed-hosts: [partner.example.com, billing.example.com]
```

---

## Observation toggles (`management.observations`)

Not under `webhooks.*`, but relevant: both sides wrap their hot path in Micrometer observations.

| Property | Default | Effect |
| --- | --- | --- |
| `management.observations.enable.webhook.receive` | `true` | Consumer receive metrics + spans. |
| `management.observations.enable.webhook.send` | `true` | Producer send metrics + spans. |
| `management.observations.enable` | `true` | Master switch for all observations. |

See [Observability → tracing](../guides/observability.md#tracing--observations).

---

## Related

- What each setting *does*: the topic [guides](../README.md#guides-by-topic).
- Replacing a bean instead of configuring it: [Beans & extension points](./beans-and-spis.md).
- Internal design rationale: [`SPEC.md`](./SPEC.md).
