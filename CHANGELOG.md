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
    and `v2a` Ed25519), secret rotation, optional replay window, type-based dispatch to `@WebhookHandler`
    methods, Bean Validation, sync/async execution, idempotency guard, failure hooks, Micrometer metrics.
  - `webhooks-producer-autoconfigure` / `webhooks-producer-starter` — declarative `@WebhookClient`
    interfaces, envelope building, `v1`/`v2a` signing with dual-signing for rotation, in-process retry with
    backoff, HTTP timeouts, destination URL allow-list (SSRF guard).
  - Standalone producer and consumer sample apps with Docker Compose and integration tests.
- Apache License 2.0, contribution guidelines, security policy, and code of conduct.
- GitHub Actions CI (quality gate, samples, release-artifact check), weekly OWASP dependency scan, Dependabot.
- Maven Central publishing setup (`release` profile).

### Changed

- Built against Spring Boot 3.5.16 (was 3.3.5).
- `@WebhookType` discovery now uses Spring's `ClassPathScanningCandidateComponentProvider` instead of
  `org.reflections` (unmaintained) — one fewer runtime dependency. Only concrete, independent (top-level or
  `static` nested) classes directly annotated with `@WebhookType` are discovered.
- Test tooling: PITest 1.30.2 / pitest-junit5-plugin 1.2.3 (JUnit Platform 1.12 support); `@MockBean` replaced
  by `@MockitoBean`.

[Unreleased]: https://github.com/jinternals/webhook/commits/main
