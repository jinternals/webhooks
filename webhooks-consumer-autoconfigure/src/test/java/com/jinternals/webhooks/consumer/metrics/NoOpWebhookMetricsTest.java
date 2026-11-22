package com.jinternals.webhooks.consumer.metrics;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NoOpWebhookMetricsTest {

    private final NoOpWebhookMetrics metrics = new NoOpWebhookMetrics();

    @Test
    void everyCallbackIsANoOpAndNeverThrows() {
        // The default must be completely inert: all five callbacks run without side effects or exceptions,
        // so the receive path is zero-cost when no MeterRegistry is present (SPEC §20.6/A4).
        assertThatCode(() -> {
                    metrics.received("contact.created");
                    metrics.verificationFailed("signature");
                    metrics.handled("contact.created", Duration.ofMillis(5));
                    metrics.deliveryLag(Duration.ofSeconds(1));
                    metrics.eventLag(Duration.ofSeconds(2));
                })
                .doesNotThrowAnyException();
    }
}
