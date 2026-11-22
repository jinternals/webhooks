package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoOpWebhookDeliveryListenerTest {

    @Test
    void doesNothingAndNeverThrows() {
        WebhookDeliveryListener listener = new NoOpWebhookDeliveryListener();
        DeliveryFailure failure = new DeliveryFailure(
                "https://crm.example.com/webhooks",
                Map.of("webhook-id", List.of("msg_1")),
                "{\"a\":1}",
                3,
                DeliveryFailure.Reason.EXHAUSTED,
                new WebhookDeliveryException("down", 500));

        assertThatCode(() -> listener.onDeliveryFailed(failure)).doesNotThrowAnyException();
    }
}
