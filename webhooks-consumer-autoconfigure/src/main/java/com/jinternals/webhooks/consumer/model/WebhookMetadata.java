package com.jinternals.webhooks.consumer.model;

import com.jinternals.webhooks.core.redact.WebhookRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable metadata handed to webhook handlers alongside the deserialized event (see SPEC.md §9).
 *
 * <p>Carries the event type, both timestamps (attempt vs. occurrence — see STANDARD_WEBHOOKS.md
 * §3a), the {@code webhook-id} (correlation / idempotency key), endpoint context, and an
 * {@code attributes} escape hatch for provider-specific fields.
 *
 * <p>The toolkit does <strong>not</strong> resolve or carry a trace/correlation id: correlation and
 * logging context are the application's concern (see SPEC.md §9b). Use Micrometer Tracing / OpenTelemetry
 * or your own servlet filter to populate the SLF4J {@code MDC}; the async handler executor propagates
 * whatever is in the request thread's {@code MDC} to the handler thread, so your context flows through
 * unchanged. The stable {@code webhook-id} is always available here via {@link #webhookId()}.
 *
 * <p>Security: the signature value and the signing secret are never placed here; the {@code headers}
 * map has {@code webhook-signature} redacted, so this object is safe to log. The deserialized
 * {@code event} is also carried, but for logging you should prefer {@link #redactedEvent()}, which
 * masks any {@code @Redact} fields (see SPEC.md §9a).
 */
public record WebhookMetadata(

        // --- Standard Webhooks envelope / headers ---
        String type,
        String webhookId,
        Instant attemptTimestamp, // webhook-timestamp header (delivery attempt time)
        Instant eventTimestamp, // body "timestamp" (when the event occurred) — nullable

        // --- Endpoint context ---
        String endpointPath,
        String sourceIp,

        // --- The deserialized event (log it via redactedEvent(), not toString()) ---
        Object event,

        // --- Escape hatch ---
        Map<String, String> headers, // all headers, lower-cased, signature REDACTED
        Map<String, Object> attributes) {

    /** Lag between now and the delivery attempt (grows with retries). */
    public Duration deliveryLag() {
        return Duration.between(attemptTimestamp, Instant.now());
    }

    /** Lag between now and the actual event time. Empty if the body had no timestamp. */
    public Optional<Duration> eventLag() {
        return Optional.ofNullable(eventTimestamp).map(t -> Duration.between(t, Instant.now()));
    }

    /** Case-insensitive header lookup (signature is redacted). */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }

    /** Typed attribute lookup from the escape-hatch map. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> attribute(String name) {
        return Optional.ofNullable((T) attributes.get(name));
    }

    /**
     * A log-safe rendering of the deserialized {@link #event()}, with every {@code @Redact} field
     * masked (see SPEC.md §9a). Use this whenever you want to log the event payload; it is the only
     * framework-sanctioned way to do so without leaking sensitive values. Reflection is cached, so
     * there is no per-call performance penalty.
     */
    public String redactedEvent() {
        return WebhookRedactor.toRedactedString(event);
    }
}
