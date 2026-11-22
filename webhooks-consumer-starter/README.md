# webhooks-consumer-starter

Spring Boot starter for **receiving** [Standard Webhooks](https://www.standardwebhooks.com/). Add this single
dependency to verify, deserialize, validate, and dispatch inbound webhooks to `@WebhookHandler` methods.

```xml
<dependency>
    <groupId>io.github.jinternals</groupId>
    <artifactId>webhooks-consumer-starter</artifactId>
    <version>${webhooks.version}</version>
</dependency>
```

This is a thin starter: it contains no code and simply pulls in
[`webhooks-consumer-autoconfigure`](../webhooks-consumer-autoconfigure) and its runtime dependencies.

See the [project README](../README.md) and the [consumer guide](../docs/guides/consumer.md).

Licensed under the [Apache License, Version 2.0](../LICENSE).
