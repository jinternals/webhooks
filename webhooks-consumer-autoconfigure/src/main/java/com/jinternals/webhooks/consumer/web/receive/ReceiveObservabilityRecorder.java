package com.jinternals.webhooks.consumer.web.receive;

import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receive-pipeline observability (see SPEC.md §20.6/A2, A4): records the received counter, both lag
 * summaries, and the "verified" log line for an accepted delivery. Delivery lag is now −
 * webhook-timestamp (grows with the sender's retries); event lag is now − body timestamp (absent when
 * the body carried no timestamp).
 *
 * <p>Single responsibility: receive-side metrics + the verified log line. It does not dispatch or time
 * the handler (that is {@link TimedHandlerDispatcher}).
 */
public class ReceiveObservabilityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReceiveObservabilityRecorder.class);

    private final WebhookMetrics metrics;

    public ReceiveObservabilityRecorder(WebhookMetrics metrics) {
        this.metrics = metrics;
    }

    public void record(String type, WebhookMetadata metadata) {
        Duration deliveryLag = metadata.deliveryLag();
        metrics.received(type);
        metrics.deliveryLag(deliveryLag);
        metadata.eventLag().ifPresent(metrics::eventLag);
        log.info(
                "Webhook verified type={} deliveryLag={}ms eventLag={}",
                type,
                deliveryLag.toMillis(),
                metadata.eventLag().map(l -> l.toMillis() + "ms").orElse("n/a"));
    }
}
