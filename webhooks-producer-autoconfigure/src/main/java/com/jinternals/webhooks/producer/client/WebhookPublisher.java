package com.jinternals.webhooks.producer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.sign.WebhookSigner;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The engine behind every {@code @WebhookClient} proxy (see SPEC.md §19): given an event object and a
 * destination name, it derives the {@code type} from the event's {@link WebhookType} annotation, builds the
 * Standard Webhooks envelope, serializes it <strong>once</strong>, signs that exact byte payload, and
 * hands the signed request to the pluggable {@link WebhookTransport}.
 *
 * <p>Stateless and thread-safe. No retry, no persistence — a failed delivery throws to the caller.
 */
public class WebhookPublisher {

    private final ObjectMapper objectMapper;
    private final WebhookSigner signer;
    private final WebhookTransport transport;
    private final SecretProvider<Destination> secretProvider;
    private final WebhookProducerProperties properties;

    public WebhookPublisher(
            ObjectMapper objectMapper,
            WebhookSigner signer,
            WebhookTransport transport,
            SecretProvider<Destination> secretProvider,
            WebhookProducerProperties properties) {
        this.objectMapper = objectMapper;
        this.signer = signer;
        this.transport = transport;
        this.secretProvider = secretProvider;
        this.properties = properties;
    }

    /**
     * Build, sign, and send the event to the named destination. When the destination declares
     * {@code additional-secrets}, the payload is <strong>dual-signed</strong> with every secret and all
     * signatures travel in one space-separated {@code webhook-signature} header, for zero-downtime
     * rotation (SPEC.md §19a).
     *
     * @param destinationName the {@code webhooks.producer.destinations.<name>} to publish to
     * @param event           a {@code @WebhookType}-annotated event object
     * @throws IllegalArgumentException if the destination is unknown or the event lacks {@code @WebhookType}
     * @throws IllegalStateException    if the payload cannot be serialized
     *
     * <p>Instrumented with {@link Observed @Observed} (SPEC.md §13): with actuator (and optionally a tracer
     * bridge) on the host classpath this emits a {@code webhook.send} timer and a tracing span. Disable via
     * the standard {@code management.observations.enable.webhook.send=false}.
     */
    @Observed(name = "webhook.send", contextualName = "webhook send")
    public void publish(String destinationName, Object event) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Cannot publish a null event to webhook destination: " + destinationName);
        }
        Destination destination = properties.getDestinations().get(destinationName);
        if (destination == null) {
            throw new IllegalArgumentException("Unknown webhook destination: " + destinationName);
        }

        String type = typeOf(event);
        String messageId = "msg_" + UUID.randomUUID();
        Instant now = Instant.now();

        String body = serialize(type, now, event);

        Map<String, List<String>> headers;
        if (signer.isEnabled()) {
            List<String> secrets = secretProvider.secretsFor(destination);
            if (secrets.isEmpty()) {
                throw new IllegalStateException(
                        "No signing secret configured for webhook destination: " + destinationName);
            }
            String signature =
                    sign(destinationName, secrets, destination.getEndpointId(), messageId, now.getEpochSecond(), body);
            headers = Map.of(
                    WebhookHeaders.ID, List.of(messageId),
                    WebhookHeaders.TIMESTAMP, List.of(Long.toString(now.getEpochSecond())),
                    WebhookHeaders.SIGNATURE, List.of(signature));
        } else {
            // Signing disabled (NoOpWebhookSigner): no secret required, no webhook-signature header.
            headers = Map.of(
                    WebhookHeaders.ID, List.of(messageId),
                    WebhookHeaders.TIMESTAMP, List.of(Long.toString(now.getEpochSecond())));
        }

        transport.send(destination.getUrl(), headers, body);
    }

    /**
     * Sign the payload with every resolved secret and combine them into one {@code webhook-signature}
     * header value (see SPEC.md §19a). With a single secret this is just that one signature; with
     * {@code additional-secrets} configured for rotation it is a space-separated list of signatures over
     * the same payload, so a receiver that accepts the primary <em>or</em> any additional secret
     * verifies successfully — zero-downtime blue/green rotation.
     *
     * <p>Defends against a custom {@link SecretProvider} that returns a non-empty list containing a
     * {@code null} or blank entry: such an entry is rejected with a message naming the destination, so a
     * delivery is never signed with a bogus secret (nor sent unsigned). The default provider already drops
     * blanks, so this guard only ever fires for a misbehaving override.
     */
    private String sign(
            String destinationName,
            List<String> secrets,
            String endpointId,
            String messageId,
            long timestamp,
            String payload) {
        StringBuilder combined = new StringBuilder();
        for (String secret : secrets) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "SecretProvider returned a null/blank signing secret for webhook destination: "
                                + destinationName);
            }
            if (combined.length() > 0) {
                combined.append(' ');
            }
            combined.append(signer.sign(secret, endpointId, messageId, timestamp, payload));
        }
        return combined.toString();
    }

    /** The {@code type} string from the event's {@link WebhookType} annotation. */
    private static String typeOf(Object event) {
        WebhookType type = event.getClass().getAnnotation(WebhookType.class);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Event " + event.getClass().getName() + " is not annotated with @WebhookType");
        }
        return type.value();
    }

    /** Serialize the full envelope {@code {type, timestamp, data}} exactly once. */
    private String serialize(String type, Instant timestamp, Object event) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("type", type);
            envelope.put("timestamp", timestamp.toString());
            JsonNode data = objectMapper.valueToTree(event);
            envelope.set("data", data);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload: " + e.getMessage(), e);
        }
    }
}
