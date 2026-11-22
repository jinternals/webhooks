package com.jinternals.webhooks.consumer.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves each callback records the intended meter against a real {@link SimpleMeterRegistry} — the same
 * contract a host's actuator-provided registry sees. Each assertion documents the operation → meter
 * mapping of SPEC §20.6/A4.
 */
class MicrometerWebhookMetricsTest {

    private SimpleMeterRegistry registry;
    private MicrometerWebhookMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerWebhookMetrics(registry);
    }

    @Test
    void receivedIncrementsACounterTaggedByType() {
        metrics.received("contact.created");
        metrics.received("contact.created");
        metrics.received("invoice.paid");

        assertThat(registry.get("webhook.received")
                        .tag("type", "contact.created")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.get("webhook.received")
                        .tag("type", "invoice.paid")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void verificationFailedIncrementsACounterTaggedByReason() {
        metrics.verificationFailed("signature");
        metrics.verificationFailed("timestamp");
        metrics.verificationFailed("signature");

        assertThat(registry.get("webhook.verification.failed")
                        .tag("reason", "signature")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.get("webhook.verification.failed")
                        .tag("reason", "timestamp")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void handledRecordsATimerTaggedByType() {
        metrics.handled("contact.created", Duration.ofMillis(40));

        var timer = registry.get("webhook.handle.duration")
                .tag("type", "contact.created")
                .timer();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40.0);
    }

    @Test
    void deliveryLagRecordsADistributionSummaryInMilliseconds() {
        metrics.deliveryLag(Duration.ofMillis(250));
        metrics.deliveryLag(Duration.ofMillis(750));

        DistributionSummary summary = registry.get("webhook.delivery.lag").summary();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(1000.0); // 250 + 750 ms
        assertThat(summary.max()).isEqualTo(750.0);
    }

    @Test
    void eventLagRecordsADistributionSummaryInMilliseconds() {
        metrics.eventLag(Duration.ofSeconds(2));

        DistributionSummary summary = registry.get("webhook.event.lag").summary();
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(2000.0); // 2s as ms
    }

    @Test
    void deliveryLagClampsNegativeValuesToZero() {
        // A sender clock ahead of ours yields a negative lag; it must be clamped to 0, not recorded as a
        // negative (which would skew the summary). Kills the mutant dropping the Math.max clamp.
        metrics.deliveryLag(Duration.ofMillis(-500));

        DistributionSummary summary = registry.get("webhook.delivery.lag").summary();
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(0.0);
        assertThat(summary.max()).isEqualTo(0.0);
    }

    @Test
    void eventLagClampsNegativeValuesToZero() {
        metrics.eventLag(Duration.ofMillis(-500));

        DistributionSummary summary = registry.get("webhook.event.lag").summary();
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(0.0);
    }

    @Test
    void lagSaturatesInsteadOfOverflowingOnExtremePositiveOrNegativeDurations() {
        // Duration.ofSeconds(Long.MAX_VALUE).toMillis() throws ArithmeticException; clampToZero must saturate
        // positive overflow to Long.MAX_VALUE and clamp huge negative durations to 0.0 without throwing.
        metrics.deliveryLag(Duration.ofSeconds(Long.MAX_VALUE));
        metrics.eventLag(Duration.ofSeconds(Long.MIN_VALUE));

        assertThat(registry.get("webhook.delivery.lag").summary().max()).isEqualTo((double) Long.MAX_VALUE);
        assertThat(registry.get("webhook.event.lag").summary().max()).isEqualTo(0.0);
    }

    @Test
    void memoizesCountersAndTimersAcrossRepeatedCallsForSameTag() {
        SimpleMeterRegistry spyRegistry = org.mockito.Mockito.spy(new SimpleMeterRegistry());
        MicrometerWebhookMetrics memoized = new MicrometerWebhookMetrics(spyRegistry);

        memoized.received("contact.created");
        memoized.received("contact.created");
        memoized.verificationFailed("signature");
        memoized.verificationFailed("signature");
        memoized.handled("contact.created", Duration.ofMillis(10));
        memoized.handled("contact.created", Duration.ofMillis(20));

        assertThat(spyRegistry
                        .get("webhook.received")
                        .tag("type", "contact.created")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(spyRegistry
                        .get("webhook.verification.failed")
                        .tag("reason", "signature")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(spyRegistry
                        .get("webhook.handle.duration")
                        .tag("type", "contact.created")
                        .timer()
                        .count())
                .isEqualTo(2L);
    }
}
