package com.jinternals.webhooks.consumer.verify;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Disabled {@link WebhookVerifier}: verifies nothing and accepts <strong>every</strong> request. Wired when
 * {@code webhooks.consumer.signature-verification-enabled=false} (see SPEC.md §8f). It ignores the secrets,
 * body, and headers entirely and never throws, so no {@code webhook-signature} header or endpoint secret is
 * required.
 *
 * <p><strong>Security.</strong> With verification off the endpoint accepts forged payloads from anyone who
 * can reach it. Only use this when message authenticity is guaranteed by the transport instead (an
 * authenticating reverse proxy / mTLS / a Spring Security resource-server chain in front of the endpoint).
 * The autoconfiguration logs a prominent WARN at every startup while this is active.
 */
public class NoOpWebhookVerifier implements WebhookVerifier {

    /**
     * Accepts unconditionally: returns normally for any input (see the security note on the class). Present
     * only to satisfy the SPI contract; it performs no HMAC and reads none of its arguments.
     */
    @Override
    public void verify(
            List<String> secrets, @Nullable String endpointId, String rawBody, Map<String, List<String>> headers) {
        // Verification disabled: accept every request without checking the signature.
    }
}
