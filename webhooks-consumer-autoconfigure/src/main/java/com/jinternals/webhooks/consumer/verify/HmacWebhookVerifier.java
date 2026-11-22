package com.jinternals.webhooks.consumer.verify;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Default {@link WebhookVerifier}: verifies an inbound webhook against an endpoint's secret(s) using the
 * Standard Webhooks symmetric {@code v1} scheme (see SPEC.md §8, §8b).
 *
 * <p>The HMAC math itself is delegated to the official {@code com.standardwebhooks} library
 * ({@link Webhook#sign}), so the {@code msg_id.timestamp.payload} scheme, HMAC-SHA256, and the
 * {@code whsec_} secret decoding all come from the audited library. This wrapper owns the surrounding
 * policy the library hard-codes and does not expose:
 *
 * <ul>
 *   <li><strong>Multi-secret acceptance</strong> for zero-downtime rotation (SPEC §8a): a signature
 *       validates if it matches <em>any</em> candidate secret;</li>
 *   <li><strong>Multi-signature acceptance</strong> (space-separated {@code webhook-signature} values),
 *       so a dual-signing sender is accepted if <em>any</em> signature matches.</li>
 * </ul>
 *
 * <p>The replay-window (timestamp freshness) check is a <strong>separate, injectable concern</strong>
 * delegated to a {@link TimestampValidator} (SPI), because the upstream library always enforces a fixed
 * ±5-minute window that it exposes no way to tune or disable. The shipped default disables it; see
 * {@link TimestampValidator}.
 *
 * <p>Signature comparison is constant-time ({@link MessageDigest#isEqual}). Throws
 * {@link WebhookVerificationException} on any failure.
 *
 * <p>A {@link Webhook} is constructed per verification. Construction only strips the {@code whsec_} prefix
 * and Base64-decodes the secret into a key array (no {@code Mac} is retained), so there is nothing worth
 * caching on a path that then computes an HMAC per candidate secret.
 */
public class HmacWebhookVerifier implements WebhookVerifier {

    private final TimestampValidator timestampValidator;

    public HmacWebhookVerifier(TimestampValidator timestampValidator) {
        this.timestampValidator = timestampValidator;
    }

    @Override
    public void verify(List<String> secrets, String endpointId, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException {
        if (secrets == null || secrets.isEmpty()) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.NO_SECRET, "No webhook secret configured for this endpoint");
        }

        WebhookSignatureHeaders parsed = WebhookSignatureHeaders.from(headers);
        timestampValidator.validate(parsed.timestamp);

        for (String secret : secrets) {
            if (matches(secret, rawBody, parsed)) {
                return; // verified against this secret
            }
        }
        throw new WebhookVerificationFailure(VerificationFailureReason.SIGNATURE, "No matching signature found");
    }

    /**
     * Whether the computed {@code v1} signature for this secret matches any of the (space-separated) header
     * signatures. Package-private so {@link DefaultWebhookVerifier} can reuse it per-secret without re-running
     * the shared header/timestamp skeleton.
     */
    boolean matches(String secret, String rawBody, WebhookSignatureHeaders parsed) {
        String expected = expectedSignature(secret, parsed.msgId, parsed.timestamp, rawBody);
        for (String versioned : parsed.signature.split(" ")) {
            String[] parts = versioned.split(",");
            if (parts.length < 2 || !"v1".equals(parts[0])) {
                continue;
            }
            if (MessageDigest.isEqual(parts[1].getBytes(), expected.getBytes())) {
                return true;
            }
        }
        return false;
    }

    /** The bare (unversioned) expected signature for the inputs, via the official library's HMAC. */
    private String expectedSignature(String secret, String msgId, long timestamp, String rawBody) {
        try {
            // Webhook#sign returns "v1,<base64>"; we compare the base64 portion.
            return doSign(newWebhook(secret), msgId, timestamp, rawBody).split(",")[1];
        } catch (WebhookSigningException e) {
            // A valid (already-constructed) secret never fails to sign, so this is unreachable in
            // practice; surface a misconfiguration rather than a silent non-match if it ever does.
            throw new IllegalStateException("Failed to compute expected signature: " + e.getMessage(), e);
        }
    }

    /**
     * Package-private seam over the official library's {@link Webhook#sign} (see SPEC.md §8). The
     * library only throws {@link WebhookSigningException} for an {@code InvalidKey}/{@code NoSuchAlgorithm}
     * error, which cannot happen for an already-constructed secret and the guaranteed {@code HmacSHA256}
     * algorithm — so the surrounding catch is otherwise unreachable. A test overrides this to throw on
     * demand and exercise that catch honestly (same approach as the producer's signer).
     */
    String doSign(Webhook webhook, String msgId, long timestamp, String rawBody) throws WebhookSigningException {
        return webhook.sign(msgId, timestamp, rawBody);
    }

    private Webhook newWebhook(String secret) {
        try {
            return new Webhook(secret);
        } catch (Exception e) {
            // EmptyWebhookSecretException or invalid base64 — a misconfiguration.
            throw new IllegalStateException("Invalid webhook secret configured: " + e.getMessage(), e);
        }
    }
}
