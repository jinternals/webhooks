package com.jinternals.webhooks.consumer.dispatch;

/**
 * Default {@link HandlerExecutor}: runs the handler <strong>inline</strong> on the request thread, so
 * the controller's {@code 2xx} is only sent after the handler completes (see SPEC.md §15). A handler
 * that throws therefore turns into the right HTTP status, and the sender's own retry machinery is the
 * back-pressure — no queue to lose work on restart. Fits the typical 15–30&nbsp;second sender budget.
 */
public class SyncHandlerExecutor implements HandlerExecutor {

    @Override
    public void execute(String type, Runnable handlerCall) {
        handlerCall.run();
    }
}
