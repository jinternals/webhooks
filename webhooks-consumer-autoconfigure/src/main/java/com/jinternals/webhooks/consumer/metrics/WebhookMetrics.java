package com.jinternals.webhooks.consumer.metrics;

import java.time.Duration;

/**
 * Opt-in consumer-side metrics for the webhook receive path (see SPEC.md §20.6/A4, STANDARD_WEBHOOKS.md
 * §11). An SPI so the library emits <strong>nothing by default</strong> ({@link NoOpWebhookMetrics}) and a
 * host that has a Micrometer {@code MeterRegistry} automatically gets {@link MicrometerWebhookMetrics} —
 * or supplies its own {@code @Bean WebhookMetrics} to integrate any backend.
 *
 * <p>All callbacks are invoked on the receive hot path and must be cheap and non-throwing; the default and
 * the Micrometer implementation both are. The toolkit holds no state of its own here — a
 * {@code MeterRegistry} is the host's, not the library's.
 *
 * <p>Suggested meters (names are the Micrometer implementation's):
 * <ul>
 *   <li>{@code webhook.received} — a counter, tagged by event {@code type};</li>
 *   <li>{@code webhook.verification.failed} — a counter, tagged by {@code reason};</li>
 *   <li>{@code webhook.handle.duration} — a timer of handler execution, tagged by {@code type};</li>
 *   <li>{@code webhook.delivery.lag} / {@code webhook.event.lag} — distribution summaries (milliseconds).</li>
 * </ul>
 */
public interface WebhookMetrics {

    /** A verified webhook of the given {@code type} was accepted for dispatch. */
    void received(String type);

    /**
     * Verification of an inbound webhook failed; {@code reason} is a low-cardinality cause
     * (e.g. {@code "signature"}, {@code "missing-headers"}) — never a secret or raw body.
     */
    void verificationFailed(String reason);

    /** A handler for {@code type} ran (successfully or not) in the given wall-clock {@code duration}. */
    void handled(String type, Duration duration);

    /** The delivery lag ({@code now − webhook-timestamp}) observed for a verified webhook. */
    void deliveryLag(Duration lag);

    /** The event lag ({@code now − body.timestamp}) observed for a verified webhook. */
    void eventLag(Duration lag);
}
