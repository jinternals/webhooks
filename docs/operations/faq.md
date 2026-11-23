# FAQ

Common questions and gotchas. For depth, each answer links to the relevant guide.

---

## General

### Does the toolkit store anything?

**No.** No delivery history, no retry queue, no idempotency store by default. The producer retries
transient failures in-process (bounded, in-memory) and then throws; the consumer returns the right HTTP
status and lets the sender's retry be the durability mechanism. Durable retry / dead-lettering is your
responsibility, built on the hooks the toolkit provides. See
[Architecture → no-storage design](../architecture.md#the-no-storage-design).

### Can I use just the consumer, or just the producer?

**Yes.** They're independent libraries that share only `webhooks-core` and meet only on the wire. Depend on
whichever side(s) you need.

### What are the requirements?

Java 21, Spring Boot 4.0.x, Maven 3.9+. See [Getting started](../getting-started.md).

### Do I need `@EnableWebhooks` or component scanning?

**No** for the consumer — it auto-configures on the classpath. The producer needs
`@EnableWebhookClients(basePackages = …)` so it can discover your `@WebhookClient` interfaces. See
[Annotations](../reference/annotations.md#enablewebhookclients).

---

## Consumer

### A valid webhook returns `400` — why?

Almost always a secret mismatch. The `400` carries a `reason`: `signature` (wrong/rotated secret or
tampering), `timestamp` (clock skew or replay window), `missing-headers` (a proxy may be stripping
`webhook-*` headers), or `no-secret` (endpoint secret not configured). See
[HTTP status codes](../reference/http-status-codes.md) and
[Verification & security](../guides/verification-and-security.md#signature-verification).

### My handler runs twice for the same event — how do I dedupe?

Senders retry, so the same `webhook-id` can arrive more than once. In order of robustness: (1) make handlers
idempotent (upsert / dedupe on your own key) — always recommended; (2) enable best-effort in-memory dedupe
(`in-memory-idempotency: true`) — per-instance, lossy on restart; (3) replace the `IdempotencyGuard` bean
with a shared store (Redis/DB). See [Consumer guide → idempotency](../guides/consumer.md#idempotency-optional-in-memory).

### Why `204` and not `200`?

A webhook receiver has nothing to return; `204 No Content` is the honest "accepted, nothing to say." See
[HTTP status codes](../reference/http-status-codes.md#why-these-choices).

### I get `204` for an event I don't handle — is that right?

Yes, under the default `ack-and-ignore` unknown-type policy: an unmodelled `type` returns `204` so the sender
**stops retrying** something you'll never handle. Set `unknown-type-policy: reject` to return `400` instead.
See [Consumer guide → unknown-type policy](../guides/consumer.md#unknown-type-policy).

### How do I validate event payloads?

Annotate your `@WebhookType` event class directly with Jakarta Bean Validation constraints (`@NotBlank`,
`@Positive`, `@Size`, …) — the event class *is* the schema, and unknown JSON fields in `data` are rejected
automatically (`400`). See [Consumer guide → payload validation](../guides/consumer.md#per-type-payload-validation).

### Should I use `sync` or `async` handlers?

Default to **`sync`** — it makes the sender's retry your durability mechanism (if you can't process it, you
don't ack it). Use `async` only when handlers are slow and the sender's timeout is tight, accepting that a
crash after the `204` but before the handler drops that delivery. See
[Reliability → sync vs async](../guides/reliability.md#sync-vs-async-handling).

### How do I keep PII out of my logs?

Mark sensitive event fields with `@Redact` and log `metadata.redactedEvent()` (never the raw event). The
`webhook-signature`, `authorization`, `proxy-authorization`, and `cookie` headers are already redacted from
`metadata.headers()`. See
[Verification & security → redaction](../guides/verification-and-security.md#redaction-safe-logging).

---

## Producer

### What happens when a delivery fails?

A fatal `4xx` throws immediately; a retryable `5xx`/`429`/transport error is retried with exponential backoff
(honoring `Retry-After`) up to `max-attempts`, then throws the last exception to your caller. On abandon, the
`WebhookDeliveryListener` hook fires once with the send-ready request. See
[Reliability](../guides/reliability.md#producer-retry--backoff).

### Does the producer honor `Retry-After`?

Yes — on a `429`/`503` carrying `Retry-After`, the next wait is `max(exponential, Retry-After)`, clamped to
`max-backoff`. It only ever raises a wait, so there's no flag to disable it. See
[Reliability → Retry-After](../guides/reliability.md#honoring-retry-after).

### How do I get durable, cross-restart retry?

The built-in retry is in-process only. For delivery that survives a receiver outage beyond the sender's
budget, capture abandons via `WebhookDeliveryListener` into your own outbox/queue and replay them with your
own worker. See [Reliability → building durable delivery](../guides/reliability.md#building-durable-delivery-on-top).

### Can I send asynchronously / through a queue?

Yes — replace the `WebhookTransport` bean. See
[Beans & extension points](../reference/beans-and-spis.md#producer-spis).

---

## Security

### Is the replay window enabled by default?

**Yes (`5m` tolerance).** Out of the box, `ClockSkewTimestampValidator` rejects captured deliveries outside
`replay-tolerance` so they cannot be replayed indefinitely. Keep clocks NTP-synced and pair with idempotent
handlers; only set `replay-window-enabled: false` for offline fixture replay. See
[Verification & security → replay window](../guides/verification-and-security.md#replay-window).

### How do I rotate a secret with no downtime?

Use `additional-secrets`. Add the new secret as an additional on the receiver, dual-sign old+new on the
producer, promote new + drop old on the receiver, then drop old on the producer — no valid delivery ever
fails. See [Verification & security → secret rotation](../guides/verification-and-security.md#secret-rotation).

### How do I resolve secrets from a secrets manager?

Replace the `SecretProvider<T>` bean (`@Bean SecretProvider<Endpoint>` on the consumer, or
`@Bean("destinationSecretProvider") SecretProvider<Destination>` on the producer). See
[Beans & extension points](../reference/beans-and-spis.md).

### How do I restrict outbound destinations (SSRF)?

Populate `webhooks.producer.security.allowed-hosts`; a blocked host fails fast before any request. For CIDR /
DNS-rebind policies, replace the `DestinationUrlValidator` bean. See
[Verification & security → SSRF guard](../guides/verification-and-security.md#destination-url-guard-ssrf).

---

## Observability

### How do I correlate logs for one delivery?

The stable `webhook-id` is on `WebhookMetadata.webhookId()` for every handler, but the toolkit writes
nothing to the `MDC` — correlation is the application's concern. Bind it (and any trace id) in a servlet
filter that runs before the webhook controller, then add `%X{webhook.id}` to your log pattern. A runnable
filter lives in the sample consumer app. See
[Observability → correlation](../guides/observability.md#webhook-id-correlation-app-owned) and
[SPEC §9b](../reference/SPEC.md#9b-correlation--trace-context-app-owned).

### Why don't I see any metrics?

Metrics are opt-in: they require a Micrometer `MeterRegistry` on the classpath (e.g.
`spring-boot-starter-actuator`). With no registry the toolkit emits nothing. See
[Observability → opt-in metrics](../guides/observability.md#opt-in-metrics).

### What's the difference between delivery lag and event lag?

`deliveryLag = now − webhook-timestamp` (age of this attempt, grows with retries); `eventLag = now −
body.timestamp` (age of the underlying event, `n/a` if absent). They separate transport delay from event
staleness. See [Observability → lag](../guides/observability.md#delivery-lag--event-lag).

---

## Related

- Full guides by topic → [docs home](../README.md)
- Operational response → [Runbook](./runbook.md)
- Design rationale → [`SPEC.md`](../reference/SPEC.md)
