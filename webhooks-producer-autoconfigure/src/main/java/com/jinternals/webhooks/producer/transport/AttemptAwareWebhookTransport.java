package com.jinternals.webhooks.producer.transport;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Optional {@link WebhookTransport} extension for transports that execute multiple delivery attempts (such as
 * {@link RetryingWebhookTransport}) and should refresh attempt-scoped headers — specifically
 * {@code webhook-timestamp} and {@code webhook-signature} — on each retry.
 *
 * <p>Per the Standard Webhooks specification ("Webhook metadata"):
 * <blockquote>
 *   Every time an attempt is retried the timestamp of the attempt is updated, while the timestamp of the
 *   original event remains the same. The unique identifier ({@code webhook-id}) remains the same no matter
 *   how many times a webhook that has failed is retried.
 * </blockquote>
 *
 * <p>Keeping this on a sub-interface with a distinct method name preserves {@link WebhookTransport} as a
 * single-method {@link FunctionalInterface} without introducing overload ambiguity for callers or test mocks.
 */
public interface AttemptAwareWebhookTransport extends WebhookTransport {

    /**
     * Deliver a webhook request, invoking {@code headersSupplier} at the start of every delivery attempt so
     * retries carry a fresh {@code webhook-timestamp} and re-computed {@code webhook-signature} over the
     * unchanged {@code webhook-id} and {@code body}.
     *
     * @param url             the absolute destination URL
     * @param headersSupplier supplies freshly-signed headers for each attempt
     * @param body            the raw JSON body (whose envelope {@code timestamp} stays fixed across retries)
     * @throws WebhookDeliveryException if delivery ultimately fails
     */
    void sendPerAttempt(String url, Supplier<Map<String, List<String>>> headersSupplier, String body);
}
