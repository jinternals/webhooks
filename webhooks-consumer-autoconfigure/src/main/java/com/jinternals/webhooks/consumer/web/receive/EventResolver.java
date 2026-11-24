package com.jinternals.webhooks.consumer.web.receive;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.config.WebhookProperties.UnknownTypePolicy;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.web.error.InvalidPayloadException;
import com.jinternals.webhooks.consumer.web.error.UnknownTypeException;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Receive-pipeline stage 2 (see SPEC.md §5, §6a, §10): from a verified envelope, decide whether the
 * event {@code type} is accepted by this endpoint and modelled by a {@code @WebhookType} class, then
 * strictly deserialize and Bean-Validate the resulting event.
 *
 * <p>An unaccepted or unmodelled type is <em>not</em> an error here: it resolves to
 * {@link Resolution.Ignored} under the {@code ACK_AND_IGNORE} policy (the caller returns {@code 204}),
 * or is raised as {@link UnknownTypeException} ({@code → 400}) under {@code REJECT}. A payload that fails
 * strict deserialization (unknown JSON fields) or validation throws ({@code → 400}).
 *
 * <p>Single responsibility: routing + deserialization + validation of the event object. It does not
 * verify signatures, build metadata, or dispatch.
 */
public class EventResolver {

    private static final Logger log = LoggerFactory.getLogger(EventResolver.class);

    private final WebhookTypeRegistry typeRegistry;
    private final PayloadValidator payloadValidator;
    private final WebhookProperties properties;
    /**
     * Strict reader for the event {@code data} node: rejects unknown JSON fields so a payload that carries
     * properties the {@code @WebhookType} class does not declare yields a {@code 400}, independent of the
     * host app's global {@link ObjectMapper} leniency (SPEC §6a).
     */
    private final ObjectReader strictEventReader;

    private final ConcurrentMap<Class<?>, ObjectReader> eventReaders = new ConcurrentHashMap<>();

    public EventResolver(
            WebhookTypeRegistry typeRegistry,
            PayloadValidator payloadValidator,
            ObjectMapper objectMapper,
            WebhookProperties properties) {
        this.typeRegistry = typeRegistry;
        this.payloadValidator = payloadValidator;
        this.properties = properties;
        this.strictEventReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Resolve the envelope to a {@link Resolution.Accepted} carrying the resolved {@code type} and the
     * validated event, or {@link Resolution.Ignored} when the type is unaccepted/unmodelled under the
     * {@code ACK_AND_IGNORE} policy. Throws {@link UnknownTypeException} under {@code REJECT},
     * {@link InvalidPayloadException} on a malformed envelope/payload, and
     * {@link com.jinternals.webhooks.consumer.web.error.PayloadValidationException} on a validation failure
     * (all → 400).
     */
    public Resolution resolve(Endpoint endpoint, Envelope envelope) {
        String type = envelope.getType();
        if (type == null || type.isBlank()) {
            // A signed body with no routable type is a malformed payload, not an "unknown type": there is
            // nothing to match against the allow-list or registry, so it is a 400 regardless of policy.
            throw new InvalidPayloadException("envelope is missing a 'type'", null);
        }

        String endpointPath = Objects.requireNonNull(endpoint.getPath(), "endpoint.path must not be null");
        if (!endpoint.accepts(type)) {
            return unknownType(type, endpointPath, "not in endpoint allow-list");
        }
        Class<?> eventClass = typeRegistry.classOf(type);
        if (eventClass == null) {
            return unknownType(type, endpointPath, "no @WebhookType class");
        }
        return Resolution.accepted(type, deserializeAndValidate(type, eventClass, envelope));
    }

    /**
     * Deserialize the verified {@code data} node into the {@code @WebhookType} class and validate the result.
     * The strict reader rejects unknown JSON fields so a payload carrying properties the class does not
     * declare fails with a 400 (SPEC §6a). It binds straight from the already-parsed data node;
     * convertValue would round-trip it through an intermediate TokenBuffer (re-serialize then re-read),
     * pure overhead on the hot path (SPEC §18). Bean Validation then enforces the constraints declared on
     * the {@code @WebhookType} class (the type IS the schema; SPEC §6a) — a violation throws → 400.
     *
     * <p>A Jackson failure here (unknown field, type mismatch, malformed {@code data}) is a sender error,
     * so it is wrapped as {@link InvalidPayloadException} (→ 400) rather than being allowed to escape as a
     * generic exception that the error handler would map to 500 and the sender would retry forever.
     *
     * <p>A missing or JSON-{@code null} {@code data} node (or a custom/coerced {@code null} result) is
     * rejected up front for the same reason: Bean Validation cannot validate {@code null} (it throws
     * {@code IllegalArgumentException}) — that would escape as a 500 and the sender would retry a payload
     * that can never succeed. A signed body with no {@code data} is a malformed sender payload (→ 400).
     */
    private Object deserializeAndValidate(String type, Class<?> eventClass, Envelope envelope) {
        JsonNode data = envelope.getData();
        if (data == null || data.isNull()) {
            throw new InvalidPayloadException(type, "envelope is missing required 'data'", null);
        }
        @Nullable Object event;
        try {
            event = eventReaders
                    .computeIfAbsent(eventClass, strictEventReader::forType)
                    .readValue(data);
        } catch (JacksonException e) {
            throw new InvalidPayloadException(type, e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new InvalidPayloadException(type, "envelope 'data' deserialized to null", null);
        }
        payloadValidator.validate(type, event);
        return event;
    }

    private Resolution unknownType(String type, String path, String reason) {
        UnknownTypePolicy policy = properties.getConsumer().getUnknownTypePolicy();
        if (policy == UnknownTypePolicy.REJECT) {
            throw new UnknownTypeException(type, path, reason);
        }
        log.warn("Unknown webhook type '{}' on {} ({}); ack-and-ignore", type, path, reason);
        return Resolution.ignored();
    }
}
