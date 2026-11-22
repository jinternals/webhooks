package com.jinternals.webhooks.consumer.dispatch;

/**
 * Default {@link HandlerFailureListener}: does nothing (see SPEC.md §20). Keeps the consumer drop-in — with
 * no host-supplied listener, an async handler failure or timeout is logged by {@link AsyncHandlerExecutor}
 * but not otherwise captured. Replace it with a {@code @Bean HandlerFailureListener} to persist or alert.
 */
public class NoOpHandlerFailureListener implements HandlerFailureListener {

    @Override
    public void onHandlerFailed(String type, Throwable cause) {
        // intentionally empty: the no-storage default captures nothing
    }

    @Override
    public void onHandlerTimeout(String type) {
        // intentionally empty: the no-storage default captures nothing
    }
}
