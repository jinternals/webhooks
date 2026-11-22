package com.jinternals.webhooks.consumer.dispatch;

/**
 * Strategy for <em>when</em> a verified webhook's {@code @WebhookHandler} runs relative to the HTTP
 * response (see SPEC.md §15, §18). The controller builds the handler invocation as a {@link Runnable}
 * and hands it here; the implementation decides whether to run it inline or off-thread.
 *
 * <ul>
 *   <li>{@link SyncHandlerExecutor} (default) runs the task <strong>inline</strong>, so the controller
 *       only returns its {@code 2xx} after the handler completes — a {@code 2xx} means "processed".</li>
 *   <li>{@link AsyncHandlerExecutor} submits the task to a bounded pool and returns <strong>immediately</strong>,
 *       so the controller responds {@code 204} before the handler runs — a {@code 2xx} means "accepted".</li>
 * </ul>
 *
 * <p>Swappable via {@code webhooks.consumer.handler.mode}, or by publishing a custom
 * {@code @Bean HandlerExecutor} (it is {@code @ConditionalOnMissingBean}).
 */
public interface HandlerExecutor {

    /**
     * Run (or schedule) the handler invocation.
     *
     * @param type        the event type, for logging/metrics tags
     * @param handlerCall the fully-bound handler invocation (dispatch + timing)
     */
    void execute(String type, Runnable handlerCall);
}
