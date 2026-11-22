package com.jinternals.webhooks.consumer.web.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.support.LogCapture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiveObservabilityRecorderTest {

    @Test
    void skipsDeliveryLagMetricAndLogsNaWhenAttemptTimestampIsAbsent() {
        // When signature verification is disabled (NoOpWebhookVerifier), a request may arrive without a
        // webhook-timestamp header -> attemptTimestamp is null -> deliveryLag is empty. Must record received
        // and log deliveryLag=n/a instead of throwing NullPointerException.
        WebhookMetrics metrics = mock(WebhookMetrics.class);
        ReceiveObservabilityRecorder recorder = new ReceiveObservabilityRecorder(metrics);
        WebhookMetadata unsignedMeta = new WebhookMetadata(
                "contact.created", "msg_1", null, null, "/webhooks/crm", "127.0.0.1", new Object(), Map.of(), Map.of());

        try (LogCapture logs = LogCapture.forClass(ReceiveObservabilityRecorder.class)) {
            recorder.record("contact.created", unsignedMeta);

            verify(metrics).received("contact.created");
            verify(metrics, never()).deliveryLag(any());
            verify(metrics, never()).eventLag(any());
            assertThat(logs.lastFormattedMessage())
                    .contains("type=contact.created")
                    .contains("deliveryLag=n/a")
                    .contains("eventLag=n/a");
        }
    }

    @Test
    void saturatingMillisClampsOverflowInBothDirections() {
        assertThat(ReceiveObservabilityRecorder.saturatingMillis(Duration.ofMillis(42)))
                .isEqualTo(42L);
        assertThat(ReceiveObservabilityRecorder.saturatingMillis(Duration.ofSeconds(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(ReceiveObservabilityRecorder.saturatingMillis(Duration.ofSeconds(Long.MIN_VALUE)))
                .isEqualTo(Long.MIN_VALUE);
    }
}
