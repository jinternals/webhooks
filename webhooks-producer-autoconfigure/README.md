# webhooks-producer-autoconfigure

A drop-in Spring Boot auto-configuration for **sending** Standard Webhooks: declare a `@WebhookClient`
interface and the library builds the envelope from a `@WebhookType` event, signs it (HMAC-SHA256, dual-signs during
secret rotation), and delivers it with in-process retry — **no storage**.

> **Add the starter, not this module directly.** `webhooks-producer-starter` depends on this
> auto-configuration and brings every runtime dependency the sender requires (Spring web/RestClient, the
> Standard Webhooks signer, Micrometer Observation). This `-autoconfigure` artifact holds the code and
> conditional beans; the starter is the single dependency a host app adds.

```xml
<dependency>
  <groupId>io.github.jinternals</groupId>
  <artifactId>webhooks-producer-starter</artifactId>
  <version>${webhooks.version}</version>
</dependency>
```

## Quick start

```yaml
webhooks:
  producer:
    destinations:
      crm:
        url: https://partner.example.com/webhooks/crm
        secret: ${CRM_WEBHOOK_SECRET}
```

```java
@WebhookClient(destination = "crm")
public interface CrmWebhooks {
    void send(ContactCreated event);
}

@SpringBootApplication
@EnableWebhookClients(basePackages = "com.example.webhooks")
class Application { }
```

```java
crmWebhooks.send(new ContactCreated("ct_1", "a@b.com"));
// → builds {"type":"contact.created","data":{…}}, signs, POSTs to "crm", retries transient failures
```

## What it does

build envelope from `@WebhookType` → sign → deliver (POST) with bounded in-process retry. A `2xx` returns
normally; a fatal `4xx` throws immediately; a retryable `5xx`/`429`/transport error is retried with
exponential backoff (honoring `Retry-After`) up to `max-attempts`, then throws `WebhookDeliveryException` and
fires the dead-letter hook.

## Documentation

- Full guide: [docs/guides/producer.md](../docs/guides/producer.md)
- Retry, backoff, `Retry-After`, dead-letter hooks: [docs/guides/reliability.md](../docs/guides/reliability.md)
- Signing, secret rotation, SSRF guard: [docs/guides/verification-and-security.md](../docs/guides/verification-and-security.md)
- Every `webhooks.producer.*` key: [docs/reference/configuration.md](../docs/reference/configuration.md#producer-webhooksproducer)
- Replaceable beans: [docs/reference/beans-and-spis.md](../docs/reference/beans-and-spis.md#producer-spis)
- Docs home: [docs/README.md](../docs/README.md)
