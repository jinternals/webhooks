package com.jinternals.webhooks.producer.sign;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.Ed25519Signatures;
import com.jinternals.webhooks.core.sign.SigningSchemeValidator;
import java.security.PrivateKey;

/**
 * Asymmetric {@code v2a} {@link WebhookSigner} (see SPEC.md §19i): signs outbound deliveries with Ed25519 over
 * the SHA-256 of {@code endpoint_id.msg_id.timestamp.payload}, using a {@code whsk_}-prefixed private key.
 * Used by {@link DefaultWebhookSigner} for every {@code whsk_}-prefixed secret.
 *
 * <p>Unlike the symmetric scheme, the {@code endpointId} is <strong>required</strong> and is part of the
 * signed content: it lets a single key pair serve many endpoints while preventing a signature made for one
 * endpoint from verifying at another. The endpoint id is never transmitted; the consumer is told the same
 * value out-of-band and rebuilds the content with it when verifying.
 *
 * <p>Implemented with JDK-native Ed25519 (Java 15+); the upstream {@code com.standardwebhooks} library is
 * HMAC-only and is not used here.
 */
public class Ed25519WebhookSigner implements WebhookSigner {

    @Override
    public String sign(String secret, String endpointId, String messageId, long timestamp, String payload) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("No signing secret configured for this destination");
        }
        if (endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpoint-id is required for a v2a (whsk_) signing key but was not configured for this destination");
        }
        if (endpointId.contains(".")) {
            throw new IllegalArgumentException("endpoint-id must not contain '.' — it is the signed-content delimiter");
        }
        SigningSchemeValidator.requireValidMessageId(messageId);
        PrivateKey privateKey = Ed25519Keys.decodePrivateKey(secret);
        String signature = Ed25519Signatures.sign(privateKey, endpointId, messageId, timestamp, payload);
        return Ed25519Signatures.VERSION + "," + signature;
    }
}
