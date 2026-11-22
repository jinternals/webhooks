package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.Ed25519Signatures;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Asymmetric {@code v2a} {@link WebhookVerifier} (see SPEC.md §8e): verifies an inbound webhook against an
 * endpoint's Ed25519 public key(s) ({@code whpk_}) by checking the signature over the SHA-256 of
 * {@code endpoint_id.msg_id.timestamp.payload}. Used by {@link DefaultWebhookVerifier} for every
 * {@code whpk_}-prefixed secret.
 *
 * <p>The {@code endpointId} is <strong>required</strong> and is rebuilt into the signed content from the
 * endpoint's own configuration — it is never read from the request — which is what prevents a webhook signed
 * for one endpoint from verifying at another that trusts the same public key.
 *
 * <p>The replay-window (timestamp freshness) check is delegated to the injected {@link TimestampValidator},
 * exactly as in {@link HmacWebhookVerifier}, so both schemes share one freshness policy. Multiple configured
 * public keys form the spec's <em>trust list</em>: the webhook is accepted if <em>any</em> key verifies
 * <em>any</em> signature in the (space-separated) {@code webhook-signature} header.
 *
 * <p>Implemented with JDK-native Ed25519 (Java 15+); the upstream {@code com.standardwebhooks} library is
 * HMAC-only and is not used here.
 */
public class Ed25519WebhookVerifier implements WebhookVerifier {

    private final TimestampValidator timestampValidator;
    private final ConcurrentMap<String, PublicKey> publicKeys = new ConcurrentReferenceHashMap<>();

    public Ed25519WebhookVerifier(TimestampValidator timestampValidator) {
        this.timestampValidator = timestampValidator;
    }

    @Override
    public void verify(
            List<String> secrets, @Nullable String endpointId, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException {
        if (secrets == null || secrets.isEmpty()) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.NO_SECRET, "No webhook secret configured for this endpoint");
        }

        WebhookSignatureHeaders parsed = WebhookSignatureHeaders.from(headers);
        timestampValidator.validate(parsed.timestamp);

        for (String secret : secrets) {
            if (matches(secret, endpointId, rawBody, parsed)) {
                return;
            }
        }
        throw new WebhookVerificationFailure(VerificationFailureReason.SIGNATURE, "No matching signature found");
    }

    /**
     * Whether this {@code whpk_} public key verifies any {@code v2a} signature in the header for the content.
     * Package-private so {@link DefaultWebhookVerifier} can reuse it per-secret without re-running the shared
     * header/timestamp skeleton, while still enforcing {@link #requireValidEndpointId(String)}.
     */
    boolean matches(String secret, @Nullable String endpointId, String rawBody, WebhookSignatureHeaders parsed)
            throws WebhookVerificationFailure {
        requireValidEndpointId(endpointId);
        PublicKey publicKey = publicKeys.computeIfAbsent(secret, Ed25519Keys::decodePublicKey);
        for (String versioned : parsed.signature.split(" ")) {
            String[] parts = versioned.split(",", 2);
            if (parts.length < 2 || !Ed25519Signatures.VERSION.equals(parts[0])) {
                continue;
            }
            if (Ed25519Signatures.verify(publicKey, endpointId, parsed.msgId, parsed.timestamp, rawBody, parts[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code v2a} verification cannot proceed without a valid, dot-free endpoint id that is part of the
     * signed content.
     */
    private static void requireValidEndpointId(@Nullable String endpointId) throws WebhookVerificationFailure {
        if (endpointId == null || endpointId.isBlank()) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.NO_SECRET,
                    "endpoint-id is required to verify a v2a (whpk_) signature but was not configured for this "
                            + "endpoint");
        }
        if (endpointId.contains(".")) {
            throw new WebhookVerificationFailure(
                    VerificationFailureReason.NO_SECRET,
                    "endpoint-id must not contain '.' — it is the signed-content delimiter");
        }
    }
}
