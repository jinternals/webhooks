package com.jinternals.webhooks.consumer.model;

import com.jinternals.webhooks.core.redact.WebhookRedactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 * map has {@code webhook-signature} and standard credential headers ({@code authorization},
 * {@code proxy-authorization}, {@code cookie}) redacted, and {@link #toString()} renders {@link #event()}
 * via {@link #redactedEvent()} so this object is genuinely safe to log (see SPEC.md §9a).
 */
public record WebhookMetadata(

        // --- Standard Webhooks envelope / headers ---
        String type,
        @Nullable String webhookId,
        @Nullable Instant attemptTimestamp, // webhook-timestamp header (delivery attempt time) — nullable when unsigned
        @Nullable Instant eventTimestamp, // body "timestamp" (when the event occurred) — nullable

        // --- Endpoint context ---
        String endpointPath,
        @Nullable String sourceIp,

        // --- The deserialized event (log it via redactedEvent(), not event().toString()) ---
        Object event,

        // --- Escape hatch ---
        Map<String, @Nullable String> headers, // all headers, lower-cased, signature + auth headers REDACTED
        Map<String, @Nullable Object> attributes) {

    /**
     * Defensively copies and freezes {@code headers} and {@code attributes} (preserving nullable map
     * values, e.g. empty header value lists flattened to {@code null} by {@code MetadataFactory}). An
     * explicit canonical constructor is used instead of a compact constructor so {@code javac} emits
     * {@code METHOD_FORMAL_PARAMETER} type-use annotations on {@code Map<String, @Nullable …>} rather than
     * {@code FIELD} targets (JDK-8307507).
     */
    public WebhookMetadata(
            String type,
            @Nullable String webhookId,
            @Nullable Instant attemptTimestamp,
            @Nullable Instant eventTimestamp,
            String endpointPath,
            @Nullable String sourceIp,
            Object event,
            @Nullable Map<String, @Nullable String> headers,
            @Nullable Map<String, @Nullable Object> attributes) {
        this.type = type;
        this.webhookId = webhookId;
        this.attemptTimestamp = attemptTimestamp;
        this.eventTimestamp = eventTimestamp;
        this.endpointPath = endpointPath;
        this.sourceIp = sourceIp;
        this.event = event;
        this.headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Lag between now and the delivery attempt (grows with retries). Empty when no valid
     * {@code webhook-timestamp} header was present (e.g. when signature verification is disabled).
     */
    public Optional<Duration> deliveryLag() {
        return deliveryLag(Clock.systemUTC());
    }

    /** Lag between {@code clock.instant()} and the delivery attempt. */
    public Optional<Duration> deliveryLag(Clock clock) {
        return Optional.ofNullable(attemptTimestamp).map(t -> Duration.between(t, clock.instant()));
    }

    /** Lag between now and the actual event time. Empty if the body had no timestamp. */
    public Optional<Duration> eventLag() {
        return eventLag(Clock.systemUTC());
    }

    /** Lag between {@code clock.instant()} and the actual event time. */
    public Optional<Duration> eventLag(Clock clock) {
        return Optional.ofNullable(eventTimestamp).map(t -> Duration.between(t, clock.instant()));
    }

    /** Case-insensitive header lookup (signature and auth headers are redacted). */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
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

    /**
     * Log-safe string representation: renders {@link #event} via {@link #redactedEvent()} rather than
     * calling {@code event.toString()}, so {@code @Redact} fields are never exposed if a caller logs the
     * metadata record directly.
     */
    @Override
    public String toString() {
        return "WebhookMetadata[type=" + type
                + ", webhookId=" + webhookId
                + ", attemptTimestamp=" + attemptTimestamp
                + ", eventTimestamp=" + eventTimestamp
                + ", endpointPath=" + endpointPath
                + ", sourceIp=" + sourceIp
                + ", event=" + redactedEvent()
                + ", headers=" + headers
                + ", attributes=" + attributes + "]";
    }
}
