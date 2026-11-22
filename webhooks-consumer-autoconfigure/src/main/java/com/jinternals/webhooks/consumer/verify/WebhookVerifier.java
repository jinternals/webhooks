package com.jinternals.webhooks.consumer.verify;

import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.util.List;
import java.util.Map;

/**
 * Pluggable <strong>message-verification SPI</strong> for inbound webhooks (see SPEC.md §8, §8f, §8e). Verifies
 * the raw request body against an endpoint's secret(s).
 *
 * <p>The shipped default {@link DefaultWebhookVerifier} selects the Standard Webhooks scheme
 * <strong>per secret, by prefix</strong>:
 *
 * <ul>
 *   <li>{@code whsec_…} → symmetric {@code v1} (HMAC-SHA256 over {@code msg_id.timestamp.payload});</li>
 *   <li>{@code whpk_…}  → asymmetric {@code v2a} (Ed25519 over the SHA-256 of
 *       {@code endpoint_id.msg_id.timestamp.payload}).</li>
 * </ul>
 *
 * Override with a {@code @Bean WebhookVerifier} to change the scheme entirely.
 *
 * <p>This is <em>message</em> authentication — it authenticates the payload itself — and is distinct from
 * <em>transport</em> authentication (a Spring Security resource-server chain in front of the endpoint); the
 * two are complementary.
 *
 * <p><strong>Disabling verification.</strong> Setting
 * {@code webhooks.consumer.signature-verification-enabled=false} wires the {@link NoOpWebhookVerifier}, which
 * accepts <strong>every</strong> request unconditionally. Only do this when authenticity is guaranteed by the
 * transport instead (an authenticating reverse proxy / mTLS / Spring Security). An endpoint with verification
 * off will accept forged payloads from anyone who can reach it.
 */
public interface WebhookVerifier {

    /**
     * Verify the raw body against any of the endpoint's candidate secrets.
     *
     * @param secrets     the endpoint's secret(s), newest first (rotation-aware); must be non-empty
     * @param endpointId  the endpoint's stable endpoint id; <strong>required for v2a</strong> (it is part of
     *                    the signed content and is never on the wire), ignored for v1. May be {@code null}
     *                    when only v1 secrets are in use
     * @param rawBody     the request body exactly as received (do NOT re-serialize before verifying)
     * @param headers     request headers as name -&gt; list of values
     * @throws WebhookVerificationException if required headers are missing, the timestamp is invalid or
     *                                      (per the {@link TimestampValidator}) outside the replay
     *                                      window, or the signature is invalid for every candidate
     *                                      secret / no secret is configured
     */
    void verify(List<String> secrets, String endpointId, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException;
}
