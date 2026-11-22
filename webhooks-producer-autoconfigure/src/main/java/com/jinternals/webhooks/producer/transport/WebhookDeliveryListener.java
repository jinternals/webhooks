package com.jinternals.webhooks.producer.transport;

/**
 * Dead-letter / failure hook for outbound deliveries the toolkit has <strong>given up on</strong> (see
 * SPEC.md §20). The toolkit itself holds no storage, so an exhausted or fatal delivery is otherwise only
 * rethrown to the caller and logged; this SPI is the single point where a host can capture that failure —
 * persist it to a dead-letter store, raise an alert, or enqueue it for manual replay — without the core
 * taking on any state.
 *
 * <p>It fires <strong>once per delivery</strong>, at the moment the toolkit stops trying (a fatal
 * {@code 4xx}, or a retryable failure whose {@code max-attempts} are exhausted — see
 * {@link RetryingWebhookTransport}), immediately <em>before</em> the exception is rethrown to the caller.
 * Intermediate retry attempts are not reported here (they are logged at {@code WARN}); this is the
 * final-failure signal only.
 *
 * <p>The default is {@link NoOpWebhookDeliveryListener}; publish a {@code @Bean WebhookDeliveryListener}
 * to receive the callback (it is {@code @ConditionalOnMissingBean}).
 *
 * <p><strong>Contract.</strong> Implementations should be fast and must not throw: a listener that throws
 * would mask the original {@link WebhookDeliveryException}. The toolkit isolates the callback so a
 * misbehaving listener cannot change the caller-visible outcome.
 */
@FunctionalInterface
public interface WebhookDeliveryListener {

    /**
     * Invoked once when a delivery is abandoned, before the failure is rethrown to the caller.
     *
     * @param failure the abandoned delivery (signed request, attempt count, last cause)
     */
    void onDeliveryFailed(DeliveryFailure failure);
}
