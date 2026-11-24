package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterDeliveryListener implements WebhookDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDeliveryListener.class);
    private static final int MAX_RETAINED_FAILURES = 1_000;

    private final ConcurrentLinkedDeque<DeliveryFailure> failures = new ConcurrentLinkedDeque<>();
    private final AtomicInteger retainedCount = new AtomicInteger();

    @Override
    public void onDeliveryFailed(DeliveryFailure failure) {
        log.error(
                "Outbound webhook moved to DLQ url={} attempts={} reason={}: {}",
                SafeUrl.sanitize(failure.url()),
                failure.attempts(),
                failure.reason(),
                SafeUrl.redactMessage(failure.cause().getMessage(), failure.url()));
        failures.addLast(failure);
        if (retainedCount.incrementAndGet() > MAX_RETAINED_FAILURES && failures.pollFirst() != null) {
            retainedCount.decrementAndGet();
        }
    }

    public List<DeliveryFailure> failures() {
        return List.copyOf(failures);
    }

    public void clear() {
        failures.clear();
        retainedCount.set(0);
    }
}
