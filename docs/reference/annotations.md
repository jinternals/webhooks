# Annotations

The complete annotation surface. Core annotations are shared; the consumer and producer each add one or two.

| Annotation | Module | On | Purpose |
| --- | --- | --- | --- |
| `@WebhookType` | core | event class | maps the class to a wire `type` string |
| `@Redact` | core | event field | masks the field in redaction-safe logging |
| `@WebhookHandler` | consumer | method | marks a handler for an event type |
| `@WebhookClient` | producer | interface | declares a typed sender for a destination |
| `@EnableWebhookClients` | producer | config class | enables `@WebhookClient` scanning |

---

## `@WebhookType`

Maps an event class to the Standard Webhooks `type` string. Used on **both** sides — the consumer resolves
`type → class` to deserialize, the producer resolves `class → type` to build the envelope.

```java
import com.jinternals.webhooks.core.annotations.WebhookType;

@WebhookType("contact.created")
public record ContactCreated(String id, String email) { }
```

- The value is the exact wire `type` (e.g. `contact.created`).
- The class is what the envelope's `data` node deserializes to / from.
- **Exactly one class per `type`** — a duplicate fails fast at startup.
- Consumer-side discovery is automatic: it scans the host app's `@SpringBootApplication` package(s); override with a custom `@Bean WebhookTypeRegistry`.
- Unknown fields bind leniently, so a sender can add fields without breaking you.

---

## `@Redact`

Marks an event field as sensitive. `WebhookMetadata.redactedEvent()` returns a string with every `@Redact`
field masked, safe to log.

```java
import com.jinternals.webhooks.core.annotations.Redact;

@WebhookType("contact.created")
public record ContactCreated(String id, @Redact String email, @Redact String ssn) { }
```

```java
@WebhookHandler
public void onCreated(ContactCreated event, WebhookMetadata metadata) {
    log.info("received {}", metadata.redactedEvent());   // email & ssn masked
    // Never log event directly — that leaks the raw values.
}
```

See [Verification & security → redaction](../guides/verification-and-security.md#redaction-safe-logging).

---

## `@WebhookHandler`

Marks a method on a Spring bean as the handler for an event type. Matching is by the **event parameter type**.

```java
import com.jinternals.webhooks.consumer.annotations.WebhookHandler;

@Component
public class ContactHandlers {

    @WebhookHandler
    public void onCreated(ContactCreated event) { … }

    @WebhookHandler
    public void onCreated(ContactCreated event, WebhookMetadata metadata) { … }   // metadata optional
}
```

- The first parameter is the `@WebhookType` event; it selects which handler runs.
- An **optional** second parameter of type `WebhookMetadata` gives correlation/lag/header context.
- Runs only **after** verification (and schema validation, if configured) — never on an unverified payload.
- In `sync` mode a thrown handler becomes a `5xx` (sender retries); in `async` mode it routes to
  `HandlerFailureListener`. See [Reliability](../guides/reliability.md#sync-vs-async-handling).

### `WebhookMetadata` fields

| Accessor | Type | Meaning |
| --- | --- | --- |
| `type()` | String | the event type |
| `webhookId()` | String | `webhook-id` (correlation / idempotency key) |
| `attemptTimestamp()` | Instant | `webhook-timestamp` (this delivery attempt) |
| `eventTimestamp()` | Instant | envelope `timestamp` (event occurrence); nullable |
| `deliveryLag()` | Duration | `now − attemptTimestamp` |
| `eventLag()` | Optional&lt;Duration&gt; | `now − eventTimestamp`; empty if no body timestamp |
| `endpointPath()` | String | the matched listen path |
| `sourceIp()` | String | caller IP |
| `header(name)` | Optional&lt;String&gt; | case-insensitive header lookup (**signature redacted**) |
| `headers()` | Map | all headers, lower-cased, `webhook-signature` redacted |
| `redactedEvent()` | String | the event with `@Redact` fields masked — log this |
| `attributes()` | Map | provider-specific fields from a `MetadataContributor` |

---

## `@WebhookClient`

Declares a typed sender interface. The library generates the implementation; each method's parameter is a
`@WebhookType` event whose type becomes the envelope `type`.

```java
import com.jinternals.webhooks.producer.annotations.WebhookClient;

@WebhookClient(destination = "crm")
public interface CrmWebhooks {
    void send(ContactCreated event);
    void send(ContactUpdated event);   // overloads route by the event's @WebhookType
}
```

- `destination` names an entry under `webhooks.producer.destinations.*`.
- Inject it like any bean; calling a method builds, signs, and delivers (with retry).
- A failed delivery throws `WebhookDeliveryException` to your caller (and fires the dead-letter hook).

See the [Producer guide](../guides/producer.md) and [Reliability](../guides/reliability.md).

---

## `@EnableWebhookClients`

Turns on scanning for `@WebhookClient` interfaces. Place it on a configuration class (typically the main app
class).

```java
import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;

@SpringBootApplication
@EnableWebhookClients(basePackages = "com.example.webhooks")
public class Application { }
```

- `basePackages` — where to look for `@WebhookClient` interfaces.
- Each discovered interface becomes an injectable bean.

---

## Related

- Beans behind these annotations → [Beans & extension points](./beans-and-spis.md)
- Where they fit end-to-end → [Getting started](../getting-started.md)
- Internal contracts → [`SPEC.md`](./SPEC.md)
