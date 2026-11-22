# webhooks-consumer-autoconfigure

A drop-in Spring Boot auto-configuration for **receiving** Standard Webhooks: signature verification,
type-based deserialization, per-type Bean Validation, routing to `@WebhookHandler` methods,
redaction-safe logging, and observability — with a deliberate **no-storage** design.

> **Add the starter, not this module directly.** `webhooks-consumer-starter` depends on this
> auto-configuration and brings every runtime dependency the listener requires (Spring MVC, Bean
> Validation, the Standard Webhooks verifier, Micrometer Observation). This `-autoconfigure` artifact
> holds the code and conditional beans; the starter is the single dependency a host app adds.

```xml
<dependency>
  <groupId>io.github.jinternals</groupId>
  <artifactId>webhooks-consumer-starter</artifactId>
  <version>${webhooks.version}</version>
</dependency>
```

No `@EnableWebhooks`, no component-scan tweaks — it auto-configures on the classpath.

## Quick start

```yaml
webhooks:
  consumer:
    endpoints:
      - path: /webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}
```

```java
@WebhookType("contact.created")
public record ContactCreated(String id, String email) { }

@Component
class ContactHandlers {
    @WebhookHandler
    void onCreated(ContactCreated event, WebhookMetadata metadata) {
        // runs only AFTER signature verification
    }
}
```

A signed POST to `/webhooks/crm` is verified and routed to `onCreated`; an invalid signature gets a `400`
and never reaches your handler.

## What it does

verify signature → (optional dedupe) → resolve `@WebhookType` → deserialize (strict) → validate the event
(Bean Validation) → dispatch (`sync` inline or `async` pool). Each failure returns the HTTP status that
makes the **sender** do the right thing.

## Documentation

- Full guide: [docs/guides/consumer.md](../docs/guides/consumer.md)
- Verification, replay window, secret rotation: [docs/guides/verification-and-security.md](../docs/guides/verification-and-security.md)
- Sync vs async, failure hooks: [docs/guides/reliability.md](../docs/guides/reliability.md)
- Correlation, lags, metrics: [docs/guides/observability.md](../docs/guides/observability.md)
- Every `webhooks.consumer.*` key: [docs/reference/configuration.md](../docs/reference/configuration.md#consumer-webhooksconsumer)
- HTTP status codes: [docs/reference/http-status-codes.md](../docs/reference/http-status-codes.md)
- Docs home: [docs/README.md](../docs/README.md)
