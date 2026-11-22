package com.jinternals.webhooks.consumer.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.annotations.Redact;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookMetadataTest {

    static class Event {
        String id = "c-1";

        @Redact
        String email = "a@b.c";
    }

    private WebhookMetadata meta(Instant attempt, Instant event, Object payload) {
        return new WebhookMetadata(
                "t", "id", attempt, event, "/p", "ip", payload, Map.of("webhook-id", "id"), Map.of("k", "v"));
    }

    @Test
    void deliveryLagIsPositiveForPastAttempt() {
        WebhookMetadata m = meta(Instant.now().minusSeconds(5), null, new Object());
        assertThat(m.deliveryLag().getSeconds()).isGreaterThanOrEqualTo(4L);
    }

    @Test
    void eventLagEmptyWhenNoEventTimestamp() {
        WebhookMetadata m = meta(Instant.now(), null, new Object());
        assertThat(m.eventLag()).isEmpty();
    }

    @Test
    void eventLagPresentWhenTimestamped() {
        WebhookMetadata m = meta(Instant.now(), Instant.now().minusSeconds(3), new Object());
        assertThat(m.eventLag()).isPresent();
        assertThat(m.eventLag().get().getSeconds()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        WebhookMetadata m = meta(Instant.now(), null, new Object());
        assertThat(m.header("WEBHOOK-ID")).contains("id");
        assertThat(m.header("nope")).isEmpty();
    }

    @Test
    void attributeLookupTyped() {
        WebhookMetadata m = meta(Instant.now(), null, new Object());
        assertThat(m.<String>attribute("k")).contains("v");
        assertThat(m.attribute("absent")).isEmpty();
    }

    @Test
    void redactedEventMasksSensitiveFields() {
        WebhookMetadata m = meta(Instant.now(), null, new Event());
        assertThat(m.redactedEvent()).contains("id=c-1").contains("email=***REDACTED***");
    }
}
