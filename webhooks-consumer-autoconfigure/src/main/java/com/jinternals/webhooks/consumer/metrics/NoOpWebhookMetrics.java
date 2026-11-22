package com.jinternals.webhooks.consumer.metrics;

import java.time.Duration;

/**
 * Default {@link WebhookMetrics}: records nothing. Keeps the library zero-cost out of the box — a host
 * with no {@code MeterRegistry} (or that has not opted in) pays nothing on the receive path. Replaced
 * automatically by {@link MicrometerWebhookMetrics} when a {@code MeterRegistry} bean is present, or by
 * any host-published {@code @Bean WebhookMetrics} (see SPEC.md §20.6/A4).
 */
public final class NoOpWebhookMetrics implements WebhookMetrics {

    @Override
    public void received(String type) {
        // no-op by design
    }

    @Override
    public void verificationFailed(String reason) {
        // no-op by design
    }

    @Override
    public void handled(String type, Duration duration) {
        // no-op by design
    }

    @Override
    public void deliveryLag(Duration lag) {
        // no-op by design
    }

    @Override
    public void eventLag(Duration lag) {
        // no-op by design
    }
}
