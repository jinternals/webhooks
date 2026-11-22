# webhooks-producer-starter

Spring Boot starter for **sending** [Standard Webhooks](https://www.standardwebhooks.com/). Add this single
dependency to sign and deliver webhooks through a declarative `@WebhookClient` interface.

```xml
<dependency>
    <groupId>io.github.jinternals</groupId>
    <artifactId>webhooks-producer-starter</artifactId>
    <version>${webhooks.version}</version>
</dependency>
```

This is a thin starter: it contains no code and simply pulls in
[`webhooks-producer-autoconfigure`](../webhooks-producer-autoconfigure) and its runtime dependencies.

See the [project README](../README.md) and the [producer guide](../docs/guides/producer.md).

Licensed under the [Apache License, Version 2.0](../LICENSE).
