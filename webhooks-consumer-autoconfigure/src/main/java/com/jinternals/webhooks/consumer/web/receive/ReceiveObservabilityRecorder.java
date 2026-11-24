package com.jinternals.webhooks.consumer.web.receive;

import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receive-pipeline observability (see SPEC.md §20.6/A2, A4): records the received counter, both lag
 * summaries, and the "verified" log line for an accepted delivery. Delivery lag is now −
 * webhook-timestamp (grows with the sender's retries; absent when verification is disabled and no
 * timestamp header was sent); event lag is now − body timestamp (absent when the body carried no
 * timestamp).
 *
 * <p>Single responsibility: receive-side metrics + the verified log line. It does not dispatch or time
 * the handler (that is {@link TimedHandlerDispatcher}).
 */
public class ReceiveObservabilityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReceiveObservabilityRecorder.class);

    private final WebhookMetrics metrics;
    private final Clock clock;

    public ReceiveObservabilityRecorder(WebhookMetrics metrics) {
        this(metrics, Clock.systemUTC());
    }

    public ReceiveObservabilityRecorder(WebhookMetrics metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    public void record(String type, WebhookMetadata metadata) {
        Instant now = clock.instant();
        Instant attemptTimestamp = metadata.attemptTimestamp();
        Instant eventTimestamp = metadata.eventTimestamp();
        Duration deliveryLag = attemptTimestamp != null ? Duration.between(attemptTimestamp, now) : null;
        Duration eventLag = eventTimestamp != null ? Duration.between(eventTimestamp, now) : null;
        metrics.received(type);
        if (deliveryLag != null) {
            metrics.deliveryLag(deliveryLag);
        }
        if (eventLag != null) {
            metrics.eventLag(eventLag);
        }
        log.info(
                "Webhook verified type={} deliveryLag={} eventLag={}",
                type,
                formatLag(deliveryLag),
                formatLag(eventLag));
    }

    private static String formatLag(@Nullable Duration lag) {
        return lag != null ? saturatingMillis(lag) + "ms" : "n/a";
    }

    static long saturatingMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
