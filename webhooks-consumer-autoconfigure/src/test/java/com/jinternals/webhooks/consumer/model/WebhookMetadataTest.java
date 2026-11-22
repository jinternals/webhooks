package com.jinternals.webhooks.consumer.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.annotations.Redact;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookMetadataTest {

    static class Event {
        String id = "c-1";

        @Redact
        String email = "a@b.c";

        @Override
        public String toString() {
            return "Event{id=" + id + ", email=" + email + "}";
        }
    }

    private WebhookMetadata meta(Instant attempt, Instant event, Object payload) {
        return new WebhookMetadata(
                "t", "id", attempt, event, "/p", "ip", payload, Map.of("webhook-id", "id"), Map.of("k", "v"));
    }

    @Test
    void deliveryLagIsPositiveForPastAttempt() {
        WebhookMetadata m = meta(Instant.now().minusSeconds(5), null, new Object());
        assertThat(m.deliveryLag()).isPresent();
        assertThat(m.deliveryLag().get().getSeconds()).isGreaterThanOrEqualTo(4L);
    }

    @Test
    void deliveryLagEmptyWhenNoAttemptTimestamp() {
        // When signature verification is disabled, webhook-timestamp may be absent -> attemptTimestamp is null.
        WebhookMetadata m = meta(null, null, new Object());
        assertThat(m.deliveryLag()).isEmpty();
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
    void lagCalculationsAcceptExplicitClockDeterministically() {
        Instant now = Instant.ofEpochSecond(1_700_000_100L);
        java.time.Clock fixed = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        WebhookMetadata m = meta(now.minusSeconds(7), now.minusSeconds(25), new Object());

        assertThat(m.deliveryLag(fixed)).contains(java.time.Duration.ofSeconds(7));
        assertThat(m.eventLag(fixed)).contains(java.time.Duration.ofSeconds(25));
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

    @Test
    void toStringRendersRedactedEventSoLoggingMetadataNeverLeaksRedactedFields() {
        Instant attempt = Instant.ofEpochSecond(1700000000L);
        Instant eventTs = Instant.ofEpochSecond(1699999990L);
        WebhookMetadata m = meta(attempt, eventTs, new Event());

        assertThat(m.toString())
                .contains("type=t")
                .contains("webhookId=id")
                .contains("attemptTimestamp=" + attempt)
                .contains("eventTimestamp=" + eventTs)
                .contains("endpointPath=/p")
                .contains("sourceIp=ip")
                .contains("email=***REDACTED***")
                .doesNotContain("a@b.c");
    }

    @Test
    void defensivelyCopiesAndFreezesHeadersAndAttributesPreservingNullValues() {
        Map<String, String> mutableHeaders = new HashMap<>();
        mutableHeaders.put("webhook-id", "id-1");
        mutableHeaders.put("x-empty", null);
        Map<String, Object> mutableAttrs = new HashMap<>();
        mutableAttrs.put("tenant", "acme");
        mutableAttrs.put("nullable", null);

        WebhookMetadata m =
                new WebhookMetadata("t", "id-1", null, null, "/p", "ip", new Object(), mutableHeaders, mutableAttrs);

        // Mutating caller maps after construction does not leak into the record.
        mutableHeaders.put("injected", "evil");
        mutableAttrs.put("injected", "evil");

        assertThat(m.headers()).containsOnlyKeys("webhook-id", "x-empty");
        assertThat(m.headers().get("webhook-id")).isEqualTo("id-1");
        assertThat(m.headers().get("x-empty")).isNull();
        assertThat(m.attributes()).containsOnlyKeys("tenant", "nullable");
        assertThat(m.attributes().get("tenant")).isEqualTo("acme");

        assertThatThrownBy(() -> m.headers().put("another", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> m.attributes().put("another", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullHeadersAndAttributesDefaultToEmptyUnmodifiableMaps() {
        WebhookMetadata m = new WebhookMetadata("t", "id-1", null, null, "/p", "ip", new Object(), null, null);

        assertThat(m.headers()).isEmpty();
        assertThat(m.attributes()).isEmpty();
        assertThatThrownBy(() -> m.headers().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> m.attributes().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }
}
