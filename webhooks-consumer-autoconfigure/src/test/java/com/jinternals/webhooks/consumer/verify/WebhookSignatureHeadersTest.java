package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link WebhookSignatureHeaders}: the shared parser for the three Standard Webhooks request
 * headers used by both the {@code v1} and {@code v2a} verifiers (SPEC.md §8, §8b). It reads the headers
 * case-insensitively, parses the timestamp to a long, and fails with the typed reason when a required header
 * is absent or the timestamp is not a number.
 */
class WebhookSignatureHeadersTest {

    @Test
    void parsesAllThreeHeaders() throws Exception {
        WebhookSignatureHeaders parsed = WebhookSignatureHeaders.from(Map.of(
                WebhookHeaders.ID, List.of("msg_1"),
                WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                WebhookHeaders.SIGNATURE, List.of("v1,sig")));

        assertThat(parsed.msgId).isEqualTo("msg_1");
        assertThat(parsed.timestamp).isEqualTo(1700000000L);
        assertThat(parsed.signature).isEqualTo("v1,sig");
    }

    @Test
    void readsHeadersCaseInsensitively() throws Exception {
        WebhookSignatureHeaders parsed = WebhookSignatureHeaders.from(Map.of(
                "Webhook-Id", List.of("msg_1"),
                "WEBHOOK-TIMESTAMP", List.of("1700000000"),
                "webhook-signature", List.of("v1,sig")));

        assertThat(parsed.msgId).isEqualTo("msg_1");
        assertThat(parsed.timestamp).isEqualTo(1700000000L);
    }

    @Test
    void failsWithMissingHeadersWhenIdIsAbsent() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void failsWithMissingHeadersWhenTimestampIsAbsent() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void failsWithMissingHeadersWhenSignatureIsAbsent() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void failsWithMissingHeadersWhenAHeaderValueListIsEmpty() {
        // A present-but-empty value list is treated as absent — firstHeader returns null.
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of(),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void failsWithTimestampWhenTimestampIsNotANumber() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.TIMESTAMP, List.of("not-a-number"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.TIMESTAMP));
    }

    @Test
    void failsWithMissingHeadersWhenIdIsBlank() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("   "),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void failsWithSignatureReasonWhenIdContainsDelimiterDot() {
        // Standard Webhooks spec: message id must not include '.' to prevent signed-content delimiter attacks.
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg.evil"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .hasMessageContaining("must not contain '.'")
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));
    }
}
