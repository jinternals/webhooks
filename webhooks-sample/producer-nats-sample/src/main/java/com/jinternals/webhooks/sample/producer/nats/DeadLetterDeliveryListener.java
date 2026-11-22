package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterDeliveryListener implements WebhookDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDeliveryListener.class);

    private final List<DeliveryFailure> failures = new CopyOnWriteArrayList<>();

    @Override
    public void onDeliveryFailed(DeliveryFailure failure) {
        log.error(
                "Outbound webhook moved to DLQ url={} attempts={} reason={}: {}",
                failure.url(),
                failure.attempts(),
                failure.reason(),
                failure.cause().getMessage());
        failures.add(failure);
    }

    public List<DeliveryFailure> failures() {
        return List.copyOf(failures);
    }

    public void clear() {
        failures.clear();
    }
}
