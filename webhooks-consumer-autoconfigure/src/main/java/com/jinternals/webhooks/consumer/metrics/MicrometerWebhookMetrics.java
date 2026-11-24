package com.jinternals.webhooks.consumer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final ConcurrentMap<String, Counter> receivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> verificationFailedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> handleTimers = new ConcurrentHashMap<>();

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
        receivedCounters
                .computeIfAbsent(
                        type,
                        t -> Counter.builder("webhook.received")
                                .description("Verified webhooks accepted for dispatch")
                                .tag("type", t)
                                .register(registry))
                .increment();
    }

    @Override
    public void verificationFailed(String reason) {
        verificationFailedCounters
                .computeIfAbsent(
                        reason,
                        r -> Counter.builder("webhook.verification.failed")
                                .description("Inbound webhooks that failed verification")
                                .tag("reason", r)
                                .register(registry))
                .increment();
    }

    @Override
    public void handled(String type, Duration duration) {
        handleTimers
                .computeIfAbsent(
                        type,
                        t -> Timer.builder("webhook.handle.duration")
                                .description("Handler execution time")
                                .tag("type", t)
                                .register(registry))
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
     * only ever records non-negative latencies, and saturate on {@code toMillis()} overflow so an extreme
     * timestamp (when the replay window is disabled) can never fail a delivery with {@code ArithmeticException}.
     */
    private static double clampToZero(Duration lag) {
        if (lag.isNegative()) {
            return 0.0;
        }
        try {
            return lag.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
