package com.jinternals.webhooks.core.sign;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Shared, scheme-aware validation of Standard Webhooks signing material, used at <strong>startup</strong> by
 * both the producer's {@code DestinationValidator} and the consumer's {@code SecretValidator} so a
 * misconfiguration fails the context at boot — fast and loud — rather than surfacing later as an opaque
 * per-request failure (see SPEC.md §14a, §19d, §8e, §19i).
 *
 * <p>The scheme is selected <strong>per secret, by prefix</strong>:
 *
 * <ul>
 *   <li>{@code whsk_…} — an Ed25519 <strong>private</strong> key (producer, {@code v2a});</li>
 *   <li>{@code whpk_…} — an Ed25519 <strong>public</strong> key (consumer, {@code v2a});</li>
 *   <li>anything else ({@code whsec_…}) — a symmetric HMAC secret ({@code v1}).</li>
 * </ul>
 *
 * <p>This validates only the <em>well-formedness</em> of the key material and the {@code endpoint-id}
 * invariants the {@code v2a} scheme requires; HMAC secret well-formedness stays with the caller (which uses
 * the upstream library). The "secret value is never logged" rule is the caller's responsibility — this class
 * never puts a secret in a message.
 *
 * <p>Utility class — all methods static. Every failure throws a {@link WebhookConfigurationException}
 * carrying a description (naming the target, never the secret) and a concrete remediation action, which each
 * side's {@code FailureAnalyzer} renders as a clean startup report.
 */
public final class SigningSchemeValidator {

    private SigningSchemeValidator() {}

    /**
     * Whether any secret in the list selects the asymmetric {@code v2a} scheme (an Ed25519 private or public
     * key). When true, an {@code endpoint-id} is required.
     */
    public static boolean usesAsymmetric(List<String> secrets) {
        for (String secret : secrets) {
            if (Ed25519Keys.isPrivateKey(secret) || Ed25519Keys.isPublicKey(secret)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate the {@code endpoint-id} against the {@code v2a} rules for a target that uses asymmetric
     * signing, and check that each Ed25519 key in the list is well-formed (so a malformed {@code whsk_} /
     * {@code whpk_} key fails at boot, not on the first signature).
     *
     * @param targetDescription a human-readable identifier for the destination/endpoint (for messages)
     * @param endpointIdProperty the config property the operator sets this target's {@code endpoint-id} on
     *                           (e.g. {@code webhooks.producer.destinations.crm.endpoint-id}), quoted in the
     *                           remediation action
     * @param endpointId        the configured endpoint id (may be {@code null}/blank — then rejected if any
     *                          asymmetric key is present)
     * @param secrets           the resolved signing material for this target
     * @param expectPrivate     {@code true} on the producer (expects {@code whsk_} private keys),
     *                          {@code false} on the consumer (expects {@code whpk_} public keys)
     * @throws WebhookConfigurationException if an asymmetric key is present without a valid endpoint id, the
     *                                       endpoint id contains a {@code '.'}, a key is the wrong half of the
     *                                       pair for this side, or a key is structurally invalid
     */
    public static void validateAsymmetric(
            String targetDescription,
            String endpointIdProperty,
            @Nullable String endpointId,
            List<String> secrets,
            boolean expectPrivate) {
        boolean asymmetric = usesAsymmetric(secrets);
        boolean endpointIdPresent = endpointId != null && !endpointId.isBlank();

        if (asymmetric && !endpointIdPresent) {
            throw new WebhookConfigurationException(
                    "endpoint-id is required for a v2a (Ed25519) signing key but is not configured for "
                            + targetDescription,
                    "Set " + endpointIdProperty + " to the stable, dot-free identifier that the other side "
                            + "verifies with (it is prepended to the signed content and is never sent on the "
                            + "wire), or switch this target to a symmetric whsec_ secret.");
        }
        if (endpointIdPresent && endpointId.contains(".")) {
            throw new WebhookConfigurationException(
                    "endpoint-id for " + targetDescription
                            + " must not contain '.' — it is the signed-content delimiter",
                    "Change " + endpointIdProperty + " to a value without any '.' character "
                            + "(e.g. 'ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb').");
        }

        for (String secret : secrets) {
            validateKeyMaterial(targetDescription, secret, expectPrivate);
        }
    }

    private static void validateKeyMaterial(String targetDescription, String secret, boolean expectPrivate) {
        if (Ed25519Keys.isPrivateKey(secret)) {
            if (!expectPrivate) {
                throw new WebhookConfigurationException(
                        targetDescription + " is configured with a private key (whsk_), but a consumer must "
                                + "verify with the public key (whpk_)",
                        "Replace the whsk_ private key with the matching whpk_ public key the producer shared "
                                + "with you. The consumer never holds the private key.");
            }
            decode(targetDescription, "whsk_ private key", () -> Ed25519Keys.decodePrivateKey(secret));
        } else if (Ed25519Keys.isPublicKey(secret)) {
            if (expectPrivate) {
                throw new WebhookConfigurationException(
                        targetDescription + " is configured with a public key (whpk_), but a producer must "
                                + "sign with the private key (whsk_)",
                        "Replace the whpk_ public key with the whsk_ private key for this destination. The "
                                + "producer signs with the private key and shares only the public key.");
            }
            decode(targetDescription, "whpk_ public key", () -> Ed25519Keys.decodePublicKey(secret));
        }
        // whsec_ (symmetric) material is validated by the caller via the upstream library.
    }

    /** Run a key decode, normalizing its {@link IllegalArgumentException} into a boot-time config failure. */
    private static void decode(String targetDescription, String keyKind, Runnable decode) {
        try {
            decode.run();
        } catch (IllegalArgumentException e) {
            throw new WebhookConfigurationException(
                    "Invalid Ed25519 " + keyKind + " for " + targetDescription + ": " + e.getMessage(),
                    "Provide a valid Ed25519 key: the prefix followed by the Base64 encoding of the raw "
                            + "32-byte key (e.g. generated by a Standard Webhooks SDK). The value here does not "
                            + "decode to a 32-byte key.",
                    e);
        }
    }

    /**
     * Whether the endpoint id is set but <em>no</em> asymmetric key is present — the one "ignored setting"
     * case a caller may want to warn about (an endpoint id on a purely {@code v1} target has no effect).
     */
    public static boolean isEndpointIdIgnored(@Nullable String endpointId, List<String> secrets) {
        return endpointId != null && !endpointId.isBlank() && !usesAsymmetric(secrets);
    }

    /**
     * Enforce the Standard Webhooks {@code webhook-id} invariant on the signing path: non-blank and free of
     * {@code '.'} (the delimiter separating {@code endpoint_id}, {@code msg_id}, {@code timestamp}, and
     * {@code payload}).
     */
    public static void requireValidMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("message-id is required for webhook signing");
        }
        if (messageId.contains(".")) {
            throw new IllegalArgumentException("message-id must not contain '.' — it is the signed-content delimiter");
        }
    }
}
