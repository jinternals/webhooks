package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookDeliveryExceptionTest {

    @Test
    void messageOnlyHasNoStatus() {
        WebhookDeliveryException e = new WebhookDeliveryException("boom");

        assertThat(e.getMessage()).isEqualTo("boom");
        assertThat(e.getStatusCode()).isEmpty();
        assertThat(e.getRetryAfter()).isEmpty();
    }

    @Test
    void messageAndCauseHasNoStatus() {
        Throwable cause = new IllegalStateException("x");
        WebhookDeliveryException e = new WebhookDeliveryException("boom", cause);

        assertThat(e.getStatusCode()).isEmpty();
        assertThat(e.getRetryAfter()).isEmpty(); // transport errors never carry a Retry-After
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void statusConstructorCarriesStatus() {
        WebhookDeliveryException e = new WebhookDeliveryException("failed with status 503", 503);

        assertThat(e.getStatusCode()).hasValue(503);
        assertThat(e.getRetryAfter()).isEmpty(); // the 2-arg status form has no Retry-After
    }

    @Test
    void zeroStatusIsTreatedAsAbsent() {
        // The transport-error path uses status 0 as the sentinel for "no HTTP response".
        WebhookDeliveryException e = new WebhookDeliveryException("transport error", 0);

        assertThat(e.getStatusCode()).isEmpty();
    }

    @Test
    void statusAndRetryAfterConstructorCarriesBoth() {
        WebhookDeliveryException e = new WebhookDeliveryException("rate limited", 429, Duration.ofSeconds(30));

        assertThat(e.getStatusCode()).hasValue(429);
        assertThat(e.getRetryAfter()).hasValue(Duration.ofSeconds(30));
    }

    @Test
    void aNullRetryAfterIsAbsent() {
        WebhookDeliveryException e = new WebhookDeliveryException("failed with status 503", 503, null);

        assertThat(e.getStatusCode()).hasValue(503);
        assertThat(e.getRetryAfter()).isEmpty();
    }
}
