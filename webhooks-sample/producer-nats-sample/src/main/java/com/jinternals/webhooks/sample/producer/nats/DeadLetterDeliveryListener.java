package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterDeliveryListener implements WebhookDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDeliveryListener.class);
    private static final int MAX_RETAINED_FAILURES = 1_000;

    private final ConcurrentLinkedDeque<DeliveryFailure> failures = new ConcurrentLinkedDeque<>();

    @Override
    public void onDeliveryFailed(DeliveryFailure failure) {
        log.error(
                "Outbound webhook moved to DLQ url={} attempts={} reason={}: {}",
                failure.url(),
                failure.attempts(),
                failure.reason(),
                failure.cause().getMessage());
        failures.addLast(failure);
        while (failures.size() > MAX_RETAINED_FAILURES) {
            failures.pollFirst();
        }
    }

    public List<DeliveryFailure> failures() {
        return List.copyOf(failures);
    }

    public void clear() {
        failures.clear();
    }
}
