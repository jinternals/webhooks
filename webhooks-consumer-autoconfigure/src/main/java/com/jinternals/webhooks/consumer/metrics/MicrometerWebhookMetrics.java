package com.jinternals.webhooks.consumer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * {@link WebhookMetrics} backed by a Micrometer {@link MeterRegistry} (see SPEC.md §20.6/A4,
 * STANDARD_WEBHOOKS.md §11). Auto-wired only when the host provides a {@code MeterRegistry} (e.g. via
 * Spring Boot Actuator); otherwise {@link NoOpWebhookMetrics} is used and nothing is emitted.
 *
 * <p>Meters produced (operation &rarr; meter):
 * <ul>
 *   <li>a verified webhook accepted &rarr; {@code webhook.received} counter, tag {@code type};</li>
 *   <li>verification failed &rarr; {@code webhook.verification.failed} counter, tag {@code reason};</li>
 *   <li>a handler ran &rarr; {@code webhook.handle.duration} timer, tag {@code type};</li>
 *   <li>delivery/event lag observed &rarr; {@code webhook.delivery.lag} / {@code webhook.event.lag}
 *       distribution summaries, in milliseconds.</li>
 * </ul>
 *
 * <p>Tags are low-cardinality by construction: {@code type} comes from the registered {@code @WebhookType} set and
 * {@code reason} from a fixed vocabulary, so the registry never sees unbounded tag values.
 */
public final class MicrometerWebhookMetrics implements WebhookMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary deliveryLag;
    private final DistributionSummary eventLag;

    public MicrometerWebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.deliveryLag = DistributionSummary.builder("webhook.delivery.lag")
                .description("Lag between the delivery attempt (webhook-timestamp) and receipt")
                .baseUnit("milliseconds")
                .register(registry);
        this.eventLag = DistributionSummary.builder("webhook.event.lag")
                .description("Lag between the event occurrence (body timestamp) and receipt")
                .baseUnit("milliseconds")
                .register(registry);
    }

    @Override
    public void received(String type) {
        Counter.builder("webhook.received")
                .description("Verified webhooks accepted for dispatch")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    @Override
    public void verificationFailed(String reason) {
        Counter.builder("webhook.verification.failed")
                .description("Inbound webhooks that failed verification")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    @Override
    public void handled(String type, Duration duration) {
        Timer.builder("webhook.handle.duration")
                .description("Handler execution time")
                .tag("type", type)
                .register(registry)
                .record(duration);
    }

    @Override
    public void deliveryLag(Duration lag) {
        deliveryLag.record(clampToZero(lag));
    }

    @Override
    public void eventLag(Duration lag) {
        eventLag.record(clampToZero(lag));
    }

    /**
     * Lag is {@code now - senderTimestamp}; a sender whose clock runs ahead of ours produces a negative
     * value that would skew the summary (and misleads as "the future"). Clamp to zero so the distribution
     * only ever records non-negative latencies.
     */
    private static double clampToZero(Duration lag) {
        return Math.max(0L, lag.toMillis());
    }
}
