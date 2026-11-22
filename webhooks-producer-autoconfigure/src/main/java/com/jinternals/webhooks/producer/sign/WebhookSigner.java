package com.jinternals.webhooks.producer.sign;

/**
 * Pluggable <strong>message-signing SPI</strong> for outbound webhooks (see SPEC.md §19, §19f, §19i). Produces
 * the {@code webhook-signature} header contribution for a given secret, message id, timestamp, and raw payload.
 *
 * <p>The shipped default {@link DefaultWebhookSigner} selects the Standard Webhooks scheme
 * <strong>per secret, by prefix</strong>:
 *
 * <ul>
 *   <li>{@code whsec_…} → symmetric {@code v1} (HMAC-SHA256 over {@code msg_id.timestamp.payload});</li>
 *   <li>{@code whsk_…}  → asymmetric {@code v2a} (Ed25519 over the SHA-256 of
 *       {@code endpoint_id.msg_id.timestamp.payload}).</li>
 * </ul>
 *
 * Override with a {@code @Bean WebhookSigner} to change the signing scheme entirely.
 *
 * <p>This is <em>message</em> authentication — it authenticates the payload itself — and is distinct from
 * <em>transport</em> authentication (an OAuth2 bearer token added by a
 * {@link com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor}); the two are complementary.
 *
 * <p><strong>Disabling signing.</strong> Setting {@code webhooks.producer.signing-enabled=false} wires the
 * {@link NoOpWebhookSigner}, whose {@link #isEnabled()} returns {@code false}: the producer then sends
 * <strong>no</strong> {@code webhook-signature} header and requires no secret, relying entirely on transport
 * auth. Only do this when the receiver authenticates the transport instead (OAuth2/mTLS) — an unsigned
 * delivery will be rejected by any receiver that verifies.
 */
public interface WebhookSigner {

    /**
     * Sign a payload for a destination.
     *
     * @param secret      the destination's signing secret: {@code whsec_…} (v1 HMAC) or {@code whsk_…}
     *                    (v2a Ed25519 private key); must be non-blank
     * @param endpointId  the destination's stable endpoint id; <strong>required for v2a</strong> (it is part
     *                    of the signed content), ignored for v1. May be {@code null} when only v1 secrets are
     *                    in use
     * @param messageId   the {@code webhook-id} for this delivery
     * @param timestamp   the signing timestamp (epoch seconds)
     * @param payload     the raw JSON body exactly as it will be sent
     * @return the {@code webhook-signature} header value, e.g. {@code v1,<base64 hmac>} or
     *         {@code v2a,<base64 ed25519>}
     * @throws IllegalArgumentException if the secret is missing/blank, or a v2a secret is used with no
     *                                  endpoint id
     * @throws IllegalStateException    if the secret is malformed, or signing otherwise fails
     */
    String sign(String secret, String endpointId, String messageId, long timestamp, String payload);

    /**
     * Whether this signer actually produces a signature. The default {@code true} covers every real signing
     * scheme. {@link NoOpWebhookSigner} returns {@code false}, which tells the producer to skip the
     * secret requirement and omit the {@code webhook-signature} header entirely (signing disabled).
     *
     * @return {@code true} if deliveries should be signed (the default); {@code false} to send unsigned
     */
    default boolean isEnabled() {
        return true;
    }
}
