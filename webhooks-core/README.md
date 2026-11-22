# webhooks-core

The shared foundation for the Webhooks Toolkit: the Standard Webhooks **wire model**, the `@WebhookType`/`@Redact`
annotations, the redactor, the type registry, and the generic `SecretProvider` SPI. Both
[`webhooks-consumer`](../webhooks-consumer-autoconfigure) and [`webhooks-producer`](../webhooks-producer-autoconfigure) depend on it;
you rarely depend on it directly.

```xml
<dependency>
  <groupId>io.github.jinternals</groupId>
  <artifactId>webhooks-core</artifactId>
  <version>${webhooks.version}</version>
</dependency>
```

## What's in here

| Type | Role |
| --- | --- |
| `Envelope` | the Standard Webhooks JSON envelope (`type`, `timestamp`, `data`). |
| `WebhookHeaders` | the `webhook-id` / `webhook-timestamp` / `webhook-signature` header contract. |
| `@WebhookType` | maps an event class to its wire `type` string (used on both sides). |
| `@Redact` | marks an event field as sensitive (`@Redact(show = 4)` optional) for redaction-safe logging. |
| `WebhookRedactor` | static `ClassValue`-cached utility that masks `@Redact` fields for safe logging. |
| `WebhookTypeRegistry` | immutable `type → Class<?>` map holder (populated by `WebhookTypeScanner` in `webhooks-consumer-autoconfigure` or custom `@Bean`). |
| `SecretProvider<T>` | SPI resolving candidate secrets for a target (`Endpoint` on the consumer, `Destination` on the producer). |
| `SymmetricKeys` / `Ed25519Keys` / `Ed25519Signatures` / `SigningSchemeValidator` | shared `whsec_` (`v1` HMAC) and `whsk_`/`whpk_` (`v2a` Ed25519) key decoding, signing/verifying, and startup validation primitives. |
| `WebhookConfigurationException` | structured configuration error (`description` + `action`) rendered by Spring Boot `FailureAnalyzer`s. |

## Documentation

- Annotations: [docs/reference/annotations.md](../docs/reference/annotations.md)
- Wire format: [docs/architecture.md](../docs/architecture.md#the-wire-format) ·
  [`STANDARD_WEBHOOKS.md`](../docs/reference/STANDARD_WEBHOOKS.md)
- Full docs home: [docs/README.md](../docs/README.md)
