package com.jinternals.webhooks.producer.sign;

import com.jinternals.webhooks.core.sign.SigningSchemeValidator;
import com.jinternals.webhooks.core.sign.SymmetricKeys;
import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;
import java.util.concurrent.ConcurrentMap;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Symmetric {@code v1} {@link WebhookSigner}: a thin wrapper over the official {@code com.standardwebhooks}
 * library that signs outbound deliveries with HMAC-SHA256 over the {@code id.timestamp.payload} content (see
 * SPEC.md §19). The symmetric counterpart of the consumer's HMAC verifier. Used by {@link DefaultWebhookSigner}
 * for every {@code whsec_}-prefixed secret.
 *
 * <p>The {@code endpointId} argument is ignored: the symmetric scheme does not sign an endpoint id (each
 * endpoint has its own unique secret, which already binds a message to its endpoint).
 *
 * <p>Validated {@link Webhook} key holders are cached in a soft-referenced {@link ConcurrentReferenceHashMap}
 * so Base64 decoding and key validation happen once per distinct secret while remaining memory-safe under
 * dynamic secret rotation.
 */
public class HmacWebhookSigner implements WebhookSigner {

    private final ConcurrentMap<String, Webhook> webhooks = new ConcurrentReferenceHashMap<>();

    @Override
    public String sign(String secret, @Nullable String endpointId, String messageId, long timestamp, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("No signing secret configured for this destination");
        }
        SigningSchemeValidator.requireValidMessageId(messageId);
        try {
            Webhook webhook = webhooks.computeIfAbsent(secret, HmacWebhookSigner::newWebhook);
            return doSign(webhook, messageId, timestamp, payload);
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

    private static Webhook newWebhook(String secret) {
        try {
            return new Webhook(SymmetricKeys.decodeSecret(secret));
        } catch (Exception e) {
            // Invalid prefix, Base64, or key length — a misconfiguration.
            throw new IllegalStateException("Invalid webhook secret configured: " + e.getMessage(), e);
        }
    }
}
