package com.jinternals.webhooks.consumer.web.receive;

import com.jinternals.webhooks.consumer.dispatch.HandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import java.time.Duration;

/**
 * Receive-pipeline final stage (see SPEC.md §15, §18): hand the validated event to the configured
 * {@link HandlerExecutor} (sync inline, or async off-thread) and record the handler's wall-clock time
 * against {@code webhook.handle.duration} (SPEC §20.6/A4). In async mode this returns before the handler
 * runs.
 *
 * <p>Single responsibility: handler execution + its duration metric. It does not verify, route, or build
 * metadata.
 */
public class TimedHandlerDispatcher {

    private final HandlerExecutor handlerExecutor;
    private final WebhookDispatcher dispatcher;
    private final WebhookMetrics metrics;

    public TimedHandlerDispatcher(
            HandlerExecutor handlerExecutor, WebhookDispatcher dispatcher, WebhookMetrics metrics) {
        this.handlerExecutor = handlerExecutor;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    public void dispatch(String type, Object event, WebhookMetadata metadata) {
        dispatcher.requireHandler(event, metadata);
        handlerExecutor.execute(type, () -> {
            long startNanos = System.nanoTime();
            try {
                dispatcher.dispatch(event, metadata);
            } finally {
                metrics.handled(type, Duration.ofNanos(System.nanoTime() - startNanos));
            }
        });
    }
}
