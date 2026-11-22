package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.core.model.WebhookHeaders;
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

    final String msgId;
    final long timestamp;
    final String signature;

    private WebhookSignatureHeaders(String msgId, long timestamp, String signature) {
        this.msgId = msgId;
        this.timestamp = timestamp;
        this.signature = signature;
    }

    /**
     * Read and validate the required headers.
     *
     * @throws WebhookVerificationFailure {@code MISSING_HEADERS} if any of the three is absent, or
     *                                    {@code TIMESTAMP} if {@code webhook-timestamp} is not a long
     */
    static WebhookSignatureHeaders from(Map<String, List<String>> headers) throws WebhookVerificationFailure {
        String msgId = firstHeader(headers, WebhookHeaders.ID);
        String msgTimestamp = firstHeader(headers, WebhookHeaders.TIMESTAMP);
        String msgSignature = firstHeader(headers, WebhookHeaders.SIGNATURE);
        if (msgId == null || msgTimestamp == null || msgSignature == null) {
            throw new WebhookVerificationFailure(VerificationFailureReason.MISSING_HEADERS, "Missing required headers");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(msgTimestamp);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "Invalid timestamp header");
        }
        return new WebhookSignatureHeaders(msgId, timestamp, msgSignature);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }
}
