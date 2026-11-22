package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryFailureTest {

    @Test
    void defensivelyCopiesAndFreezesHeadersAndValueLists() {
        List<String> mutableValues = new ArrayList<>(List.of("msg_1"));
        Map<String, List<String>> mutableHeaders = new HashMap<>();
        mutableHeaders.put("webhook-id", mutableValues);
        mutableHeaders.put("x-null-values", null);

        DeliveryFailure failure = new DeliveryFailure(
                "https://crm.example.com/webhooks",
                mutableHeaders,
                "{\"a\":1}",
                2,
                DeliveryFailure.Reason.EXHAUSTED,
                new WebhookDeliveryException("down", 503));

        // Mutating the caller's map or value list after construction cannot affect the record.
        mutableValues.add("msg_mutated");
        mutableHeaders.put("injected", List.of("evil"));

        assertThat(failure.headers()).containsOnlyKeys("webhook-id", "x-null-values");
        assertThat(failure.headers().get("webhook-id")).containsExactly("msg_1");
        assertThat(failure.headers().get("x-null-values")).isEmpty();

        // Both the outer map and inner lists are unmodifiable.
        assertThatThrownBy(() -> failure.headers().put("another", List.of("v")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> failure.headers().get("webhook-id").add("v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullHeadersDefaultToEmptyUnmodifiableMap() {
        DeliveryFailure failure = new DeliveryFailure(
                "https://crm.example.com/webhooks",
                null,
                "{}",
                1,
                DeliveryFailure.Reason.FATAL,
                new WebhookDeliveryException("bad", 400));

        assertThat(failure.headers()).isEmpty();
        assertThatThrownBy(() -> failure.headers().put("k", List.of("v")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
