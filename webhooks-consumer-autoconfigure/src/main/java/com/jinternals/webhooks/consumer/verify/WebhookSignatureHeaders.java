package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the three Standard Webhooks headers ({@code webhook-id}, {@code webhook-timestamp},
 * {@code webhook-signature}) a verifier needs, independent of signing scheme (see SPEC.md §8, §8b). Shared by
 * {@link HmacWebhookVerifier} ({@code v1}) and {@link Ed25519WebhookVerifier} ({@code v2a}) so the header
 * contract lives in one place.
 *
 * <p>An immutable value read from the request headers. {@code webhook-timestamp} is parsed to a {@code long}
 * here so both schemes share the same replay-window check; the signing scheme differs only in <em>what</em>
 * is signed, never in these transport headers.
 */
final class WebhookSignatureHeaders {

    /** Upper bound on {@code webhook-id} length to prevent oversized idempotency keys and log lines. */
    static final int MAX_ID_LENGTH = 256;

    /** Upper bound on joined {@code webhook-signature} header length to bound tokenization against hostile headers. */
    static final int MAX_SIGNATURE_HEADER_LENGTH = 4096;

    final String msgId;
    final long timestamp;
    final String signature;
    final String[] tokens;

    private WebhookSignatureHeaders(String msgId, long timestamp, String signature) {
        this.msgId = msgId;
        this.timestamp = timestamp;
        this.signature = signature;
        this.tokens = signature.split(" ");
    }

    /**
     * Matches RFC 9110 §5.3 proxy header folding ({@code ", "}) immediately preceding another
     * {@code <version>,<base64>} signature token so proxy-folded {@code webhook-signature} headers verify
     * identically to space-delimited headers without loosening single-signature comma validation.
     */
    private static final java.util.regex.Pattern PROXY_FOLDED_SIGNATURES =
            java.util.regex.Pattern.compile(",\\s+(?=[A-Za-z0-9]+,)");

    /**
     * Read and validate the required headers in a single pass.
     *
     * @throws WebhookVerificationFailure {@code MISSING_HEADERS} if any of the three is absent (or
     *                                    {@code webhook-id} is blank), {@code SIGNATURE} if
     *                                    {@code webhook-id} is duplicated, exceeds {@link #MAX_ID_LENGTH},
     *                                    contains whitespace/control characters or {@code '.'} (the
     *                                    signed-content delimiter), or {@code webhook-signature} exceeds
     *                                    {@link #MAX_SIGNATURE_HEADER_LENGTH}, or {@code TIMESTAMP} if
     *                                    {@code webhook-timestamp} is duplicated or not a canonical base-10
     *                                    {@code long}
     */
    static WebhookSignatureHeaders from(Map<String, List<String>> headers) throws WebhookVerificationFailure {
        String msgId = null;
        String msgTimestamp = null;
        List<String> sigValues = new ArrayList<>(2);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            if (key.equalsIgnoreCase(WebhookHeaders.ID)) {
                for (String value : values) {
                    if (msgId != null) {
                        throw new WebhookVerificationFailure(
                                VerificationFailureReason.SIGNATURE,
                                "Multiple " + WebhookHeaders.ID + " headers are not allowed");
                    }
                    msgId = value;
                }
            } else if (key.equalsIgnoreCase(WebhookHeaders.TIMESTAMP)) {
                for (String value : values) {
                    if (msgTimestamp != null) {
                        throw new WebhookVerificationFailure(
                                VerificationFailureReason.TIMESTAMP,
                                "Multiple " + WebhookHeaders.TIMESTAMP + " headers are not allowed");
                    }
                    msgTimestamp = value;
                }
            } else if (key.equalsIgnoreCase(WebhookHeaders.SIGNATURE)) {
                sigValues.addAll(values);
            }
        }

        String msgSignature =
                PROXY_FOLDED_SIGNATURES.matcher(String.join(" ", sigValues)).replaceAll(" ");
        if (msgId == null || msgId.isBlank() || msgTimestamp == null || msgSignature.isBlank()) {
            throw new WebhookVerificationFailure(VerificationFailureReason.MISSING_HEADERS, "Missing required headers");
        }
        if (msgId.length() > MAX_ID_LENGTH || hasInvalidIdChar(msgId)) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.SIGNATURE,
                    "Invalid webhook-id header: must be at most "
                            + MAX_ID_LENGTH
                            + " printable non-whitespace characters and must not contain '.'");
        }
        if (msgSignature.length() > MAX_SIGNATURE_HEADER_LENGTH) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.SIGNATURE, "Invalid webhook-signature header: exceeds maximum length");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(msgTimestamp);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "Invalid timestamp header");
        }
        if (!Long.toString(timestamp).equals(msgTimestamp)) {
            throw new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "Invalid timestamp header");
        }
        return new WebhookSignatureHeaders(msgId, timestamp, msgSignature);
    }

    private static boolean hasInvalidIdChar(String msgId) {
        for (int i = 0; i < msgId.length(); i++) {
            char c = msgId.charAt(i);
            if (c <= ' ' || c == '.' || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
