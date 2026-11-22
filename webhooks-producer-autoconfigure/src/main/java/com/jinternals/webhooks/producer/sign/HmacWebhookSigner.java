package com.jinternals.webhooks.producer.sign;

import com.jinternals.webhooks.core.sign.SigningSchemeValidator;
import com.jinternals.webhooks.core.sign.SymmetricKeys;
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;

/**
 * Symmetric {@code v1} {@link WebhookSigner}: a thin wrapper over the official {@code com.standardwebhooks}
 * library that signs outbound deliveries with HMAC-SHA256 over the {@code id.timestamp.payload} content (see
 * SPEC.md §19). The symmetric counterpart of the consumer's HMAC verifier. Used by {@link DefaultWebhookSigner}
 * for every {@code whsec_}-prefixed secret.
 *
 * <p>The {@code endpointId} argument is ignored: the symmetric scheme does not sign an endpoint id (each
 * endpoint has its own unique secret, which already binds a message to its endpoint).
 */
public class HmacWebhookSigner implements WebhookSigner {

    @Override
    public String sign(String secret, String endpointId, String messageId, long timestamp, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("No signing secret configured for this destination");
        }
        SigningSchemeValidator.requireValidMessageId(messageId);
        try {
            return doSign(newWebhook(secret), messageId, timestamp, payload);
        } catch (WebhookSigningException e) {
            throw new IllegalStateException("Failed to sign webhook payload: " + e.getMessage(), e);
        }
    }

    /**
     * Delegates to the underlying library's {@code sign}. Package-private and overridable so a test can
     * force the otherwise-unreachable {@link WebhookSigningException} path (a valid secret never fails to
     * sign, but the library declares the checked exception and we must handle it).
     */
    String doSign(Webhook webhook, String messageId, long timestamp, String payload) throws WebhookSigningException {
        return webhook.sign(messageId, timestamp, payload);
    }

    private Webhook newWebhook(String secret) {
        try {
            return new Webhook(SymmetricKeys.decodeSecret(secret));
        } catch (Exception e) {
            // Invalid prefix, Base64, or key length — a misconfiguration.
            throw new IllegalStateException("Invalid webhook secret configured: " + e.getMessage(), e);
        }
    }
}
