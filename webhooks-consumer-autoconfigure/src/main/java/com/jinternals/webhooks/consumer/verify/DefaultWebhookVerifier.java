package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.util.List;
import java.util.Map;

/**
 * The shipped default {@link WebhookVerifier}: selects the Standard Webhooks verification scheme
 * <strong>per secret, by prefix</strong> (see SPEC.md §8e), so an endpoint can be verified with symmetric or
 * asymmetric keys — or a mix of both during a scheme migration — with no extra configuration flag.
 *
 * <ul>
 *   <li>{@code whpk_…} (Ed25519 public key) → verified by {@link Ed25519WebhookVerifier} ({@code v2a});</li>
 *   <li>anything else ({@code whsec_…}) → verified by {@link HmacWebhookVerifier} ({@code v1}).</li>
 * </ul>
 *
 * <p>The webhook is accepted if <em>any</em> configured secret verifies <em>any</em> signature in the
 * (space-separated) {@code webhook-signature} header — this is the spec's trust-list / rotation model, and it
 * spans schemes, so a {@code v1}+{@code v2a} dual-signed delivery is accepted while a sender migrates.
 *
 * <p>This dispatcher owns the shared skeleton exactly once — the empty-secret guard, the required-header
 * parse, and the single replay-window ({@link TimestampValidator}) check — then tries each secret with the
 * matcher its prefix selects. The two delegate verifiers hold no per-secret state.
 */
public class DefaultWebhookVerifier implements WebhookVerifier {

    private final HmacWebhookVerifier hmac;
    private final Ed25519WebhookVerifier ed25519;
    private final TimestampValidator timestampValidator;

    public DefaultWebhookVerifier(TimestampValidator timestampValidator) {
        this.timestampValidator = timestampValidator;
        this.hmac = new HmacWebhookVerifier(timestampValidator);
        this.ed25519 = new Ed25519WebhookVerifier(timestampValidator);
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
            if (matches(secret, endpointId, rawBody, parsed)) {
                return; // verified against this secret
            }
        }
        throw new WebhookVerificationFailure(VerificationFailureReason.SIGNATURE, "No matching signature found");
    }

    /** Route one secret to the scheme its prefix selects. */
    private boolean matches(String secret, String endpointId, String rawBody, WebhookSignatureHeaders parsed)
            throws WebhookVerificationFailure {
        if (Ed25519Keys.isPrivateKey(secret)) {
            throw new IllegalStateException(
                    "Endpoint is configured with an Ed25519 private key (whsk_); a consumer must verify with the "
                            + "public key (whpk_)");
        }
        if (Ed25519Keys.isPublicKey(secret)) {
            return ed25519.matches(secret, endpointId, rawBody, parsed);
        }
        return hmac.matches(secret, rawBody, parsed);
    }
}
