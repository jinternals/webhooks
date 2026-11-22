# `consumer-auth-sample` — OAuth2 Resource Server + Standard Webhooks Receiver

Standalone Spring Boot application (`:9095`) demonstrating **dual-layer inbound webhook security**: a Spring
Security OAuth2 Resource Server filter chain (`401`) in front of the Webhooks Toolkit's signature + replay
verifier (`400`).

## How it works

```mermaid
flowchart LR
    Req["POST /webhooks/crm"] --> JWT{"1. Spring Security (@Order 1)<br/>Bearer JWT valid + scope?"}
    JWT -- "missing / bad JWT" --> E401["401 Unauthorized<br/>(caller rejected)"]
    JWT -- "valid JWT" --> Sig{"2. Webhooks Toolkit<br/>webhook-signature + 5m window valid?"}
    Sig -- "bad / stale sig" --> E400["400 Bad Request<br/>(tampered/replayed payload)"]
    Sig -- "valid sig" --> Handler["3. @WebhookHandler<br/>AuthenticatedContactHandlers"]
    Handler --> Ok["204 No Content"]
```

### Why keep both layers

- **Transport auth (`Authorization: Bearer <jwt>` → `401`):** Authenticates *who is calling* at the HTTP edge
  (`SCOPE_webhooks:deliver`) before the webhook controller is invoked.
- **Message auth (`webhook-signature` → `400`):** Authenticates that the *raw JSON payload* was signed by the
  secret holder and was not modified by an intermediary or proxy.

## Run & test

```bash
# Run @SpringBootTest (proves 401 on missing JWT, 400 on valid JWT + bad sig, 204 when both pass)
mvn -pl consumer-auth-sample test

# Run live with producer-auth-sample via Docker Compose
docker compose -f docker-compose.auth.yml up --build -d
```
