# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x`, minor releases may
contain breaking changes; they will always be called out under **Changed** or **Removed**.

## [Unreleased]

### Added

- Initial public release of the Webhooks Toolkit for Spring Boot (`io.github.jinternals`):
  - `webhooks-core` — Standard Webhooks wire model, `@WebhookType` / `@Redact`, redactor, type registry,
    secret SPI, Ed25519 key/signature utilities.
  - `webhooks-consumer-autoconfigure` / `webhooks-consumer-starter` — signature verification (`v1` HMAC-SHA256
    and `v2a` Ed25519), secret rotation, replay-window protection (enabled by default, `5m` tolerance),
    type-based dispatch to `@WebhookHandler` methods, Bean Validation, sync/async execution, idempotency guard,
    failure hooks, Micrometer metrics.
  - `webhooks-producer-autoconfigure` / `webhooks-producer-starter` — declarative `@WebhookClient`
    interfaces, envelope building, `v1`/`v2a` signing with dual-signing for rotation, in-process retry with
    backoff, HTTP timeouts, destination URL allow-list (SSRF guard).
  - Standalone sample apps under `webhooks-sample/` with Docker Compose and integration tests:
    - `producer-sample` & `consumer-sample` — quickstart `v1` HMAC + `v2a` Ed25519 + `x-trace-id` correlation.
    - `producer-nats-sample` & `consumer-nats-sample` — zero-webhook-loss NATS JetStream queueing, fresh
      per-attempt signing, NATS KV CAS idempotency with crash lease recovery, and DLQs.
    - `producer-auth-sample` & `consumer-auth-sample` — OAuth2/JWT bearer transport auth (`401`) layered in
      front of Standard Webhooks signature verification (`400`).
    - `spi-sample` — single-app producer+consumer coexistence and custom SPI bean overrides.
- Apache License 2.0, contribution guidelines, security policy, and code of conduct.
- GitHub Actions CI (quality gate, samples, release-artifact check), weekly OWASP dependency scan, Dependabot.
- Maven Central publishing setup (`release` profile).

### Changed

- Upgraded to **Spring Boot 4.2.0** (Spring Framework 7, Jackson 3 `tools.jackson.*`, JSpecify
  `@NullMarked` / `@Nullable` null-safety annotations, `spring-boot-dependencies` BOM in root `webhooks` POM,
  and modular starters `spring-boot-starter-webmvc`, `spring-boot-starter-restclient`,
  `spring-boot-starter-aspectj`, `spring-boot-starter-security-oauth2-resource-server`).
- `@WebhookType` discovery now uses Spring's `ClassPathScanningCandidateComponentProvider` instead of
  `org.reflections` (unmaintained) — one fewer runtime dependency. Only concrete, independent (top-level or
  `static` nested) classes directly annotated with `@WebhookType` are discovered.
- Test tooling: JaCoCo 0.8.16, PITest 1.30.2 / pitest-junit5-plugin 1.2.3; `@MockBean` replaced by
  `@MockitoBean`.
- `WebhookMetadata.deliveryLag()` now returns `Optional<Duration>` (matching `eventLag()`) so unsigned
  deliveries without a `webhook-timestamp` header never NPE, and `WebhookMetadata.toString()` renders
  `redactedEvent()` with `authorization` / `proxy-authorization` / `cookie` headers masked.
- Consumer SPIs (`WebhookVerifier`, `TimestampValidator`, `VerificationFailureReason`) now throw the
  library-owned unchecked `com.jinternals.webhooks.consumer.verify.WebhookVerificationException` rather than
  leaking `com.standardwebhooks.exceptions.WebhookVerificationException`.
- Standard Webhooks specification alignment:
  - Enabled `webhooks.consumer.replay-window-enabled=true` (`5m` tolerance) by default.
  - Enforced `whsec_` prefix and `24..64` decoded key bytes (`SymmetricKeys`) for symmetric `v1` secrets at
    startup and runtime.
  - Rejected blank or `.`-containing `webhook-id` values on both consumer header parsing and producer signing.
  - Updated `RetryingWebhookTransport` (`AttemptAwareWebhookTransport`) and `WebhookPublisher` to refresh
    `webhook-timestamp` and re-compute `webhook-signature` on every retry attempt while keeping `webhook-id`
    and the serialized JSON envelope `timestamp` constant.
- Virtual-thread & hot-path improvements: replaced `synchronized` in `InMemoryIdempotencyGuard` with
  `ReentrantLock`, switched `WebhookRedactor` to `ClassValue`, cached `WebhookPublisher` `@WebhookType` lookups
  per event class, serialized outbound envelopes in a single streaming Jackson pass without intermediate
  `ObjectNode` trees, cached inbound `Envelope` and per-event-type `ObjectReader` instances, cached decoded
  Ed25519/HMAC keys in `ConcurrentReferenceHashMap` and bounded Micrometer meters in `ConcurrentHashMap`,
  enforced defensive immutability on `DeliveryFailure` and `WebhookMetadata`, added `Clock` injection
  (`ObjectProvider<Clock>`) across consumer and producer, tightened internal helper visibility, and added
  JSpecify `@NullMarked` / `@Nullable` package and API annotations.

### Fixed

- Ordered consumer and producer auto-configurations after Spring Boot's
  `org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration` so the fallback
  `ObservationRegistry.NOOP` bean never pre-empts the host's real `ObservationRegistry`.
- `DestinationValidator` now honours `webhooks.producer.signing-enabled=false`, validates symmetric `whsec_`
  secrets at startup, and redacts embedded credentials/query tokens in `URISyntaxException` messages.
- Enforced `endpoint-id` presence and dot-freeness on `DefaultWebhookVerifier` `v2a` verification, rejected
  wrong-half Ed25519 keys cleanly at runtime, hardened comma splitting (`split(",", 2)`), and eliminated
  timestamp / lag / `Retry-After` arithmetic overflows.
- `AsyncHandlerExecutor.close()` now drains in-flight and queued `204`-acknowledged handlers up to `timeout`
  before `shutdownNow()`, routing any remaining unexecuted tasks to `HandlerFailureListener`, and guards
  against both post-shutdown task drops (custom `RejectedExecutionHandler`) and duplicate timeout/completion
  failure callbacks (`task.cancel(false)` / `task.isCancelled()`).
- `WebhookClientRegistrar` now uses `ClassUtils.getPackageName`, resolves classes via the thread context
  `ClassLoader` for Spring Boot DevTools compatibility, excludes `@interface` annotation types from
  `@WebhookClient` scanning/validation, and deduplicates client bean definitions across multiple
  `@EnableWebhookClients` configurations.
- `WebhookEndpointRegistry` now applies `RequestMappingHandlerMapping#getBuilderConfiguration()` so registered
  `RequestMappingInfo` routes honour Spring MVC's `PathPatternParser` and host path-matching settings, and
  `WebhookConsumerWebAutoConfiguration` is gated on `@ConditionalOnBean(name = "requestMappingHandlerMapping")`.
- Enforced `http`/`https` schemes in `DestinationValidator` and `AllowListDestinationUrlValidator` (with
  bracketed IPv6 host normalization), rejected duplicate consumer endpoint `path`s and blank `@WebhookType`
  values at startup, restricted `@WebhookType` to `@Target(ElementType.TYPE)`, joined multi-line
  `webhook-signature` headers per RFC 9110 §5.3, and validated positive (`>= 1ms`) HTTP connect/read and async
  handler timeouts at bind time.
- Redacted embedded `userinfo` and fragment-stripped query credentials in `SafeUrl.redactMessage` without
  mangling `@` in root-URL queries/fragments, passed `URI.create(url)` in `RestClientWebhookTransport` so
  percent-encoded query strings are never double-encoded, treated syntactically invalid URI failures as
  non-retryable in `RetryingWebhookTransport`, isolated `webhookProducerRestClient`
  (`defaultCandidate = false` + `@Qualifier`) with managed `HttpClient` shutdown and
  `ObjectProvider<RestClient.Builder>`, and notified `WebhookDeliveryListener` when retry backoff is
  interrupted.
- Rejected JSON literal `null` envelopes with `400 Bad Request` (`InvalidPayloadException`), released
  `IdempotencyGuard` reservations on `Error` as well as `RuntimeException`, protected committed entries in
  `InMemoryIdempotencyGuard` against late `release()` without sliding TTL on rejected duplicates, failed
  eagerly with `500` on missing `@WebhookHandler` before `async` submission or `webhook.received` metric
  recording, supported generic bridge and overridden `@WebhookHandler` methods plus package-private
  `@WebhookClient` `default` methods, skipped registering `EndpointResolvingInterceptor` when `endpoints` is
  empty (while including trailing-slash variants), and pre-hashed `v2a` Ed25519 payloads once per verification
  with a signature count cap.
- Isolated consumer `SecretProvider<Endpoint>` (`defaultCandidate = false` + `ObjectProvider`) so a custom
  `@Bean("destinationSecretProvider")` in dual producer+consumer apps never backs off consumer secret
  resolution, rejected duplicate `webhook-id` / `webhook-timestamp` headers and non-canonical timestamp strings,
  normalized RFC 9110 `, `-folded `webhook-signature` headers, guarded comma-only `X-Forwarded-For` headers and
  ISO-8601 numeric-offset event timestamps in `MetadataFactory`, fast-failed oversized `Content-Length`
  requests before opening the servlet `InputStream`, and included `spring-boot-starter-restclient`
  (`spring-boot-starter-jackson`) in `webhooks-producer-autoconfigure` for out-of-the-box `java.time`
  serialization in non-web apps.
- Configured virtual-thread `HttpClient` executor on `webhookProducerHttpClient` (preventing per-request OS
  thread creation in `JdkClientHttpRequestFactory`), completed JSpecify `@NullMarked` / `@Nullable` coverage
  across all 28 packages (`lombok.addNullAnnotations = jspecify`), injected `JsonMapper` in auto-configurations,
  added `Automatic-Module-Name` manifests and reproducible `<project.build.outputTimestamp>`, capped
  `webhook-id`/`webhook-signature` lengths and `v1` signature counts (`16`), unwrapped checked handler
  exceptions (`UndeclaredThrowableException`) in `AsyncHandlerExecutor`, cached parsed `URI`s and `@WebhookClient`
  `default`-method `MethodHandle`s, skipped throwaway enqueue signing on `AttemptAwareWebhookTransport` queue
  transports, and hardened NATS/auth/SPI samples and non-root Dockerfiles.

[Unreleased]: https://github.com/jinternals/webhooks/commits/main
