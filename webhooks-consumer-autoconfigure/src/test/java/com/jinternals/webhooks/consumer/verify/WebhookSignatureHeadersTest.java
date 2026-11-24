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
                WebhookHeaders.ID,
                List.of("msg_1"),
                WebhookHeaders.TIMESTAMP,
                List.of("1700000000"),
                WebhookHeaders.SIGNATURE,
                List.of("v1,sig"),
                "content-type",
                List.of("application/json")));

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

    @Test
    void joinsMultipleSignatureHeaderLinesWithSpace() throws Exception {
        java.util.Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put(WebhookHeaders.ID, List.of("msg_1"));
        headers.put(WebhookHeaders.TIMESTAMP, List.of("1700000000"));
        headers.put("Webhook-Signature", List.of("v1,sigA", "v1,sigB"));
        headers.put("webhook-signature", List.of("v2a,sigC"));

        WebhookSignatureHeaders parsed = WebhookSignatureHeaders.from(headers);

        assertThat(parsed.signature).isEqualTo("v1,sigA v1,sigB v2a,sigC");
    }

    @Test
    void failsWithMissingHeadersWhenSignatureIsBlankOrMappedToNull() {
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("   "))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));

        java.util.Map<String, List<String>> nullSignatureList = new java.util.HashMap<>();
        nullSignatureList.put(WebhookHeaders.ID, List.of("msg_1"));
        nullSignatureList.put(WebhookHeaders.TIMESTAMP, List.of("1700000000"));
        nullSignatureList.put(WebhookHeaders.SIGNATURE, null);

        assertThatThrownBy(() -> WebhookSignatureHeaders.from(nullSignatureList))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void rejectsDuplicateIdOrTimestampNonCanonicalTimestampsAndNormalizesProxyFoldedSignatures() throws Exception {
        // 1) Duplicate webhook-id (either multi-valued or mixed-case keys) -> SIGNATURE failure (no smuggling)
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1", "msg_2"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));

        // 2) Duplicate webhook-timestamp -> TIMESTAMP failure
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000", "1700000001"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.TIMESTAMP));

        // 3) Non-canonical timestamps ("+1700000000", "01700000000", "-0") -> TIMESTAMP failure
        for (String nonCanonical : List.of("+1700000000", "01700000000", "-0")) {
            assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                            WebhookHeaders.ID, List.of("msg_1"),
                            WebhookHeaders.TIMESTAMP, List.of(nonCanonical),
                            WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                    .isInstanceOf(WebhookVerificationFailure.class)
                    .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                            .isEqualTo(VerificationFailureReason.TIMESTAMP));
        }

        // 4) RFC 9110 §5.3 proxy ", "-folded webhook-signature normalizes to space-separated tokens,
        //    while non-whitespace trailing comma ("v1,sigA,extra") remains intact (and fails matching).
        WebhookSignatureHeaders folded = WebhookSignatureHeaders.from(Map.of(
                WebhookHeaders.ID, List.of("msg_1"),
                WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                WebhookHeaders.SIGNATURE, List.of("v1,sigA, v2a,sigB")));
        assertThat(folded.signature).isEqualTo("v1,sigA v2a,sigB");

        WebhookSignatureHeaders tampered = WebhookSignatureHeaders.from(Map.of(
                WebhookHeaders.ID, List.of("msg_1"),
                WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                WebhookHeaders.SIGNATURE, List.of("v1,sigA,extra")));
        assertThat(tampered.signature).isEqualTo("v1,sigA,extra");
    }

    @Test
    void enforcesIdAndSignatureLengthBoundsAndPrintableNonWhitespaceIdCharacters() throws Exception {
        // Exact boundary acceptance: 256-char id (with printable boundary chars '!' and '~') + 4096-char signature.
        String maxValidId = "m!~" + "a".repeat(WebhookSignatureHeaders.MAX_ID_LENGTH - 3);
        String maxValidSig = "v1," + "a".repeat(WebhookSignatureHeaders.MAX_SIGNATURE_HEADER_LENGTH - 3);
        WebhookSignatureHeaders atLimit = WebhookSignatureHeaders.from(Map.of(
                WebhookHeaders.ID, List.of(maxValidId),
                WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                WebhookHeaders.SIGNATURE, List.of(maxValidSig)));
        assertThat(atLimit.msgId).hasSize(WebhookSignatureHeaders.MAX_ID_LENGTH);
        assertThat(atLimit.signature).hasSize(WebhookSignatureHeaders.MAX_SIGNATURE_HEADER_LENGTH);

        // Over-limit id (257 chars) -> SIGNATURE failure.
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of(maxValidId + "x"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));

        // Over-limit signature (4097 chars) -> SIGNATURE failure.
        assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                        WebhookHeaders.ID, List.of("msg_1"),
                        WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                        WebhookHeaders.SIGNATURE, List.of(maxValidSig + "x"))))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));

        // Whitespace (' ', '\t') and ASCII DEL (0x7f) inside webhook-id -> SIGNATURE failure.
        for (String badId : List.of("msg 1", "msg\t1", "msg\u007f1", "msg_1 ")) {
            assertThatThrownBy(() -> WebhookSignatureHeaders.from(Map.of(
                            WebhookHeaders.ID, List.of(badId),
                            WebhookHeaders.TIMESTAMP, List.of("1700000000"),
                            WebhookHeaders.SIGNATURE, List.of("v1,sig"))))
                    .isInstanceOf(WebhookVerificationFailure.class)
                    .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                            .isEqualTo(VerificationFailureReason.SIGNATURE));
        }

        // Unrelated header mapped to null is ignored cleanly.
        java.util.Map<String, List<String>> withNullOther = new java.util.LinkedHashMap<>();
        withNullOther.put("x-unrelated", null);
        withNullOther.put(WebhookHeaders.ID, List.of("msg_ok"));
        withNullOther.put(WebhookHeaders.TIMESTAMP, List.of("1700000000"));
        withNullOther.put(WebhookHeaders.SIGNATURE, List.of("v1,sig"));
        assertThat(WebhookSignatureHeaders.from(withNullOther).msgId).isEqualTo("msg_ok");
    }
}
