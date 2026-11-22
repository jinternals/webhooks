package com.jinternals.webhooks.sample.producer.spi;

import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sample {@link WebhookDeliveryListener} SPI implementation (see {@code docs/reference/beans-and-spis.md}).
 *
 * <p>Replaces the default {@code NoOpWebhookDeliveryListener} via {@code @ConditionalOnMissingBean} and
 * captures abandoned deliveries (fatal {@code 4xx} or exhausted retries) into a bounded in-memory ring
 * representing an outbox / dead-letter store that a background worker could replay later.
 */
@Component
public class DeadLetterDeliveryListener implements WebhookDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDeliveryListener.class);
    private static final int MAX_RETAINED = 100;

    private final ConcurrentLinkedDeque<DeliveryFailure> deadLetters = new ConcurrentLinkedDeque<>();

    @Override
    public void onDeliveryFailed(DeliveryFailure failure) {
        log.warn(
                "Dead-lettered webhook delivery url={} attempts={} reason={}: {}",
                failure.url(),
                failure.attempts(),
                failure.reason(),
                failure.cause().getMessage());
        deadLetters.addLast(failure);
        while (deadLetters.size() > MAX_RETAINED) {
            deadLetters.pollFirst();
        }
    }

    public List<DeliveryFailure> deadLetters() {
        return List.copyOf(deadLetters);
    }

    public void clear() {
        deadLetters.clear();
    }
}
