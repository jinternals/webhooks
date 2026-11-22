package com.jinternals.webhooks.producer.sign;

/**
 * Disabled {@link WebhookSigner}: signs nothing. Wired when {@code webhooks.producer.signing-enabled=false}
 * (see SPEC.md §19f). {@link #isEnabled()} returns {@code false}, which tells the producer to require no
 * signing secret and to omit the {@code webhook-signature} header entirely — deliveries go out
 * <strong>unsigned</strong>.
 *
 * <p><strong>Security.</strong> Only use this when message authenticity is guaranteed by the transport
 * instead (OAuth2 bearer token / mTLS). A receiver that verifies signatures will reject unsigned deliveries.
 */
public class NoOpWebhookSigner implements WebhookSigner {

    @Override
    public boolean isEnabled() {
        return false;
    }

    /**
     * Never invoked: the producer skips signing entirely when {@link #isEnabled()} is {@code false}. Present
     * only to satisfy the SPI; throws to make an accidental call loud rather than silently returning a bogus
     * value that could be put on the wire.
     */
    @Override
    public String sign(String secret, String endpointId, String messageId, long timestamp, String payload) {
        throw new UnsupportedOperationException(
                "Signing is disabled (webhooks.producer.signing-enabled=false); NoOpWebhookSigner does not sign");
    }
}
