# Webhooks Toolkit — Sample Apps

Two **standalone** Spring Boot apps that demonstrate the toolkit from both ends, each depending on the
published `webhooks-*` libraries as a starter would — add one dependency, get everything:

| App | Dependency (starter) | Port | Role |
| --- | --- | --- | --- |
| [`producer-sample`](producer-sample) | `webhooks-producer-starter` | `9090` | **Sends** signed webhooks via a `@WebhookClient` |
| [`consumer-sample`](consumer-sample) | `webhooks-consumer-starter` | `9091` | **Receives**, verifies, validates, and dispatches webhooks |

Each app adds a single **starter** dependency; the starter transitively pulls in that side's
auto-configuration (`webhooks-*-autoconfigure`) and every runtime dependency it needs — the canonical
Spring Boot starter experience.

These are **not** part of the libraries' `webhooks-parent` reactor. They consume the *published*
artifacts, so they build and run independently — exactly like a real downstream app would.

The producer's `POST /publish/contact-created` signs a `contact.created` envelope and delivers it to the
consumer's `POST /webhooks/crm`; the consumer verifies the signature, deserializes + validates the
payload, and runs the matching `@WebhookHandler`. A parallel **asymmetric** path —
`POST /publish/contact-created-v2a` → `POST /webhooks/crm-v2a` — shows the same flow signed with Ed25519
(`v2a`) instead of HMAC (`v1`), so you can compare the two schemes side by side.

## Prerequisites

Build + install the libraries into your local Maven repo first (the samples resolve
`0.0.1-SNAPSHOT` from there):

```bash
# from the repository root — installs the starters + autoconfigure + core
mvn -pl webhooks-consumer-starter,webhooks-producer-starter -am install -DskipTests
```

## Build + test the samples

```bash
# from this directory (webhooks-sample/)
mvn install
```

This builds both apps and runs their unit + context-load tests.

## Run locally (Maven)

```bash
# terminal 1 — the receiver
mvn -pl consumer-sample spring-boot:run      # :9091

# terminal 2 — the sender
mvn -pl producer-sample spring-boot:run      # :9090
```

Then publish a webhook through the producer; the consumer handles it:

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

## Run with Docker

Both apps are containerized and wired together with Compose (the producer targets the consumer by its
service name). Build context is the repository root, so each image builds the libraries from source
before building the sample.

```bash
# from this directory (webhooks-sample/)
docker compose up --build
```

- producer → http://localhost:9090
- consumer → http://localhost:9091

## Cross-library integration test (Docker)

`integration-test.sh` brings both apps up with Compose, publishes a `contact.created` through the
producer, and asserts the consumer actually handled it (log line + `webhook_received_total` metric):

```bash
# from this directory (webhooks-sample/)
./integration-test.sh
```

It tears the stack down on exit. Requires Docker (with the Compose plugin) and `curl`.

## What each app shows

- **`producer-sample`** — `@EnableWebhookClients(clients = {CrmWebhooks.class, CrmWebhooksV2a.class})` (the
  zero-scan, explicit registration path), two `@WebhookClient` interfaces (one per destination), and a
  controller that triggers a signed send. See [producer-sample/](producer-sample).
- **`consumer-sample`** — `@WebhookType` event POJOs (the type *is* the schema, via Bean Validation),
  `@WebhookHandler` beans for both a multi-type and a single-type endpoint, and a `MetadataContributor`.
  Includes `smoke-test.sh`, `sign.py`, `requests.http`, and load tests. See
  [consumer-sample/](consumer-sample).
- **Both signing schemes** ([SPEC §19i](../docs/reference/SPEC.md#19i-asymmetric-signing--ed25519-v2a-whsk_))
  — the `demo` destination / `/webhooks/crm` endpoint use `v1` (symmetric HMAC, `whsec_`); the `demo-v2a`
  destination / `/webhooks/crm-v2a` endpoint use `v2a` (asymmetric Ed25519, `whsk_` private key to sign,
  `whpk_` public key to verify, with a shared `endpoint-id`). The scheme is chosen purely by the key
  prefix; handler code is identical either way.
- **End-to-end trace/correlation** ([SPEC §9b](../docs/reference/SPEC.md#9b-correlation--trace-context-app-owned))
  — the toolkit writes nothing to the `MDC`, so correlation is the app's job, shown across both apps: the
  producer seeds a trace id (`PublishController`) and copies it onto the outbound webhook with a
  `WebhookRequestInterceptor` (`TraceHeaderWebhookInterceptor`); the consumer reads that `x-trace-id` header
  (plus the stable `webhook-id`) into its `MDC` in a servlet filter (`TraceContextFilter`) that runs before
  the webhook controller. Both apps render the id with `%X{traceId}`, so one trace spans both services.
