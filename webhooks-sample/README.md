# Webhooks Toolkit — Sample Apps

Standalone Spring Boot applications demonstrating the Webhooks Toolkit against the **published starter
artifacts** (`webhooks-consumer-starter` and `webhooks-producer-starter`). None of these apps are part of the
core library reactor — the core libraries stay 100% storage-free and broker-free, while these samples show
both the zero-dependency defaults and full production extensions (NATS JetStream zero-loss queueing, OAuth2
JWT bearer transport auth, and custom SPI beans).

## Sample catalog

| Sample module(s) | Port(s) | What it demonstrates | Compose / runner |
| --- | --- | --- | --- |
| [`producer-sample`](producer-sample) + [`consumer-sample`](consumer-sample) | `9090` / `9091` | **Quickstart:** `@WebhookClient`, `@WebhookType`, `@WebhookHandler`, symmetric `v1` (`whsec_`) + asymmetric `v2a` (`whsk_`/`whpk_`), `@Redact`, Bean Validation, `MetadataContributor`, and `x-trace-id` MDC correlation. | [`docker-compose.yml`](docker-compose.yml) · [`integration-test.sh`](integration-test.sh) |
| [`producer-nats-sample`](producer-nats-sample) + [`consumer-nats-sample`](consumer-nats-sample) | `9092` / `9093` | **Zero-webhook-loss queueing (NATS JetStream):** durable outbound stream (`NatsJetStreamWebhookTransport`) + relay worker with **fresh per-attempt signing** (replay-window safe) + durable consumer inbox (`DurableInboxHandlers` awaiting `PublishAck` before `204`) + distributed `NatsKvIdempotencyGuard` + inbound/outbound DLQs. | [`docker-compose.nats.yml`](docker-compose.nats.yml) · [`integration-test-nats.sh`](integration-test-nats.sh) |
| [`producer-auth-sample`](producer-auth-sample) + [`consumer-auth-sample`](consumer-auth-sample) | `9094` / `9095` | **Dual-layer transport + message auth:** producer `WebhookRequestInterceptor` minting short-lived OAuth2/HS256 JWT `Authorization: Bearer` tokens per attempt; consumer Spring Security OAuth2 Resource Server (`401`, validating `iss` + `aud` + `SCOPE_webhooks:deliver`) in front of Standard Webhooks signature verification (`400`). | [`docker-compose.auth.yml`](docker-compose.auth.yml) · [`integration-test-auth.sh`](integration-test-auth.sh) |
| [`spi-sample`](spi-sample) | `9096` | **Custom SPIs & single-app coexistence:** both starters in one app, overriding `@Bean SecretProvider<Endpoint>`, `@Bean("destinationSecretProvider") SecretProvider<Destination>`, reserve-then-commit `SharedStoreIdempotencyGuard`, `PrivateIpBlockingUrlValidator` (`DestinationUrlValidator`), `HandlerFailureListener`, and `WebhookDeliveryListener`. | `mvn -pl spi-sample spring-boot:run` |

> [!CAUTION]
> **Demo credentials — never use them in production.** Every secret and key in this directory
> (`whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw`, `whsk_4uKP…` / `whpk_uWKK…`, `demo-jwt-hs256-…`) is public so
> the samples run out of the box. In production, generate unique secrets/keys and inject them from a secret
> manager via environment variables or `SecretProvider`.

---

## Prerequisites

Install the toolkit starters into your local Maven repository first:

```bash
# from the repository root
mvn -pl webhooks-consumer-starter,webhooks-producer-starter -am install -DskipTests
```

## Build & test all 7 sample apps

```bash
# from webhooks-sample/ — runs all unit + @SpringBootTest suites offline in parallel
mvn -T 1C clean verify
```

---

## 1. Quickstart (`producer-sample` + `consumer-sample`)

```bash
# Run with Docker Compose + end-to-end test
./integration-test.sh
```

Or run with Maven in two terminals (`mvn -pl consumer-sample spring-boot:run` on `:9091` and
`mvn -pl producer-sample spring-boot:run` on `:9090`):

```bash
# v1 (symmetric HMAC): /publish/contact-created -> /webhooks/crm
curl -i -X POST http://localhost:9090/publish/contact-created \
  -H 'content-type: application/json' \
  -d '{"id":"c-1","fullName":"Ada Lovelace","email":"ada@example.com"}'

# v2a (asymmetric Ed25519): /publish/contact-created-v2a -> /webhooks/crm-v2a
curl -i -X POST http://localhost:9090/publish/contact-created-v2a \
  -H 'content-type: application/json' \
  -d '{"id":"c-2","fullName":"Grace Hopper","email":"grace@example.com"}'
```

---

## 2. Zero-webhook-loss stack (`producer-nats-sample` + `consumer-nats-sample`)

Brings up `nats:2.10-alpine -js` + `producer-nats-sample` (`:9092`) + `consumer-nats-sample` (`:9093`). See
the full architectural walkthrough in [Zero-webhook-loss queueing](../docs/guides/zero-loss-queueing.md).

```bash
# End-to-end test (builds images, starts stack, verifies delivery + metrics, tears down)
./integration-test-nats.sh

# Or run interactively:
docker compose -f docker-compose.nats.yml up --build -d
```

---

## 3. OAuth2 JWT transport auth (`producer-auth-sample` + `consumer-auth-sample`)

See [Transport authentication](../docs/guides/transport-authentication.md).

```bash
# End-to-end test (asserts 401 without JWT, then 202 + handler log with JWT + HMAC)
./integration-test-auth.sh

# Or run interactively:
docker compose -f docker-compose.auth.yml up --build -d

curl -i -X POST http://localhost:9094/publish/contact-created \
  -H 'content-type: application/json' \
  -d '{"id":"c-auth-1","fullName":"Ada Lovelace","email":"ada@example.com"}'
```

---

## 4. Custom SPI overrides (`spi-sample`)

See [Beans & extension points](../docs/reference/beans-and-spis.md).

```bash
mvn -pl spi-sample test
```
