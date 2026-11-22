package com.jinternals.webhooks.consumer.dispatch;

/**
 * Failure hook for <strong>async</strong> handler execution (see SPEC.md §15, §20). In {@code async} mode
 * the controller has already returned {@code 204} ("accepted") before the handler runs, so a handler that
 * throws or exceeds its timeout cannot be surfaced to the sender as a non-2xx — the failure would otherwise
 * be invisible. This SPI is the single point where a host can observe those failures: persist the delivery
 * for replay, raise an alert, or feed a dead-letter store, without the toolkit taking on any state.
 *
 * <p>It is an {@code async}-only concern. In the default {@code sync} mode a handler that throws propagates
 * to the HTTP layer and becomes the right status the sender retries on, so there is nothing hidden to
 * report and {@link SyncHandlerExecutor} never calls this listener.
 *
 * <p>The default is {@link NoOpHandlerFailureListener}; publish a {@code @Bean HandlerFailureListener} to
 * receive the callbacks (it is {@code @ConditionalOnMissingBean}).
 *
 * <p><strong>Contract.</strong> Callbacks run on the handler pool thread. Implementations should be fast
 * and must not throw: the executor isolates a throwing listener (it is logged), but a slow listener holds a
 * pool thread.
 */
public interface HandlerFailureListener {

    /**
     * Invoked when an async handler threw. The delivery was already acknowledged with {@code 204}, so this
     * is the only signal that it did not complete.
     *
     * @param type  the event type that was being handled
     * @param cause the exception the handler threw
     */
    void onHandlerFailed(String type, Throwable cause);

    /**
     * Invoked when an async handler exceeded {@code webhooks.consumer.handler.timeout} and was interrupted.
     * The delivery was already acknowledged with {@code 204}.
     *
     * @param type the event type whose handler timed out
     */
    void onHandlerTimeout(String type);
}
