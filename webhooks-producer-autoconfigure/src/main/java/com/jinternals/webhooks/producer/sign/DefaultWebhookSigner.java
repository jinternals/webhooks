package com.jinternals.webhooks.producer.sign;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import org.jspecify.annotations.Nullable;

/**
 * The shipped default {@link WebhookSigner}: selects the Standard Webhooks signing scheme
 * <strong>per secret, by prefix</strong> (see SPEC.md §19i), so a destination can use symmetric or asymmetric
 * signing — or mix both during a scheme migration — with no extra configuration flag.
 *
 * <ul>
 *   <li>{@code whsk_…} (Ed25519 private key) → delegates to {@link Ed25519WebhookSigner} ({@code v2a});</li>
 *   <li>anything else ({@code whsec_…}) → delegates to {@link HmacWebhookSigner} ({@code v1}).</li>
 * </ul>
 *
 * <p>The per-secret dispatch is what makes {@code additional-secrets} rotation work across schemes: the
 * publisher signs with every configured secret and this signer routes each one to the right engine, so a
 * single space-separated {@code webhook-signature} header can carry a {@code v1} and a {@code v2a} signature
 * side by side during a migration.
 *
 * <p>Stateless and thread-safe; the two delegate signers hold no per-secret state.
 */
public class DefaultWebhookSigner implements WebhookSigner {

    private final WebhookSigner hmac = new HmacWebhookSigner();
    private final WebhookSigner ed25519 = new Ed25519WebhookSigner();

    @Override
    public String sign(String secret, @Nullable String endpointId, String messageId, long timestamp, String payload) {
        if (Ed25519Keys.isPublicKey(secret)) {
            throw new IllegalArgumentException(
                    "Destination is configured with an Ed25519 public key (whpk_); a producer must sign with the "
                            + "private key (whsk_)");
        }
        WebhookSigner delegate = Ed25519Keys.isPrivateKey(secret) ? ed25519 : hmac;
        return delegate.sign(secret, endpointId, messageId, timestamp, payload);
    }
}
