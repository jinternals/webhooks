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
- Unknown JSON fields inside the `data` object are **rejected** (`400`) by the strict event reader, so adding fields means updating the receiver's `@WebhookType` model first.

---

## `@Redact`

Marks an event field as sensitive. `WebhookMetadata.redactedEvent()` returns a string with every `@Redact`
field masked, safe to log.

```java
import com.jinternals.webhooks.core.annotations.Redact;

@WebhookType("contact.created")
public record ContactCreated(String id, @Redact String email, @Redact(show = 4) String ssn) { }
```

- `show` (`int`, default `0`) — number of trailing characters to leave visible (e.g. `@Redact(show = 4)` on `"123-45-6789"` renders `"*******6789"`; if the value has `<= show` characters it is fully redacted).

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
    public void onUpdated(ContactUpdated event, WebhookMetadata metadata) { … }   // metadata optional

    @WebhookHandler
    public void onDeleted(WebhookMetadata metadata, ContactDeleted event) { … }   // parameter order is flexible
}
```

- Must declare **one** `@WebhookType` event parameter (which selects the handler) and **optionally** one `WebhookMetadata` parameter, in **either order**.
- Runs only **after** signature verification, strict JSON deserialization, and Jakarta Bean Validation — never on an unverified or invalid payload.
- In `sync` mode a thrown handler becomes a `5xx` (sender retries); in `async` mode it routes to
  `HandlerFailureListener`. See [Reliability](../guides/reliability.md#sync-vs-async-handling).

### `WebhookMetadata` fields

| Accessor | Type | Meaning |
| --- | --- | --- |
| `type()` | String | the event type |
| `webhookId()` | String | `webhook-id` (correlation / idempotency key); nullable when unsigned |
| `attemptTimestamp()` | Instant | `webhook-timestamp` (this delivery attempt); nullable when unsigned |
| `eventTimestamp()` | Instant | envelope `timestamp` (event occurrence); nullable |
| `deliveryLag()` | Optional&lt;Duration&gt; | `now − attemptTimestamp`; empty if no valid `webhook-timestamp` |
| `eventLag()` | Optional&lt;Duration&gt; | `now − eventTimestamp`; empty if no body timestamp |
| `endpointPath()` | String | the matched listen path |
| `sourceIp()` | String | caller IP |
| `event()` | Object | the deserialized `@WebhookType` event instance (**contains unredacted fields — do not log directly**) |
| `header(name)` | Optional&lt;String&gt; | case-insensitive header lookup (`webhook-signature`, `authorization`, `proxy-authorization`, `cookie` redacted) |
| `headers()` | Map&lt;String, String&gt; | all headers, lower-cased, with `webhook-signature` and auth/cookie headers redacted |
| `redactedEvent()` | String | the event with `@Redact` fields masked — log this |
| `attributes()` | Map&lt;String, Object&gt; | provider-specific fields populated by `MetadataContributor` beans |
| `attribute(name)` | Optional&lt;T&gt; | typed convenience lookup into `attributes()` |

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
@EnableWebhookClients(clients = CrmWebhooks.class)   // or basePackages / basePackageClasses
public class Application { }
```

- `clients` (`Class<?>[]`, recommended) — explicit `@WebhookClient` interfaces registered directly with zero classpath scanning.
- `basePackageClasses` (`Class<?>[]`) — refactor-safe anchor classes whose packages are scanned for `@WebhookClient` interfaces.
- `basePackages` (`String[]`) — package names scanned for `@WebhookClient` interfaces.
- If no selector is specified (`@EnableWebhookClients`), the package of the annotated configuration class is scanned by default; multiple selectors take the deduplicated union.

---

## Related

- Beans behind these annotations → [Beans & extension points](./beans-and-spis.md)
- Where they fit end-to-end → [Getting started](../getting-started.md)
- Internal contracts → [`SPEC.md`](./SPEC.md)
