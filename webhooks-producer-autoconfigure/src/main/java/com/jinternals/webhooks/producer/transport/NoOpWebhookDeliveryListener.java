package com.jinternals.webhooks.producer.transport;

/**
 * Default {@link WebhookDeliveryListener}: does nothing (see SPEC.md §20). Keeps the producer drop-in —
 * with no host-supplied listener an abandoned delivery is simply rethrown to the caller and logged, exactly
 * as before this hook existed. Replace it with a {@code @Bean WebhookDeliveryListener} to capture failures.
 */
public class NoOpWebhookDeliveryListener implements WebhookDeliveryListener {

    @Override
    public void onDeliveryFailed(DeliveryFailure failure) {
        // intentionally empty: the no-storage default captures nothing
    }
}
