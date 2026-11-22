package com.jinternals.webhooks.core.sign;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.List;

/**
 * The Standard Webhooks asymmetric ({@code v2a}) signature primitive (see SPEC.md §8e / §19i), computed with
 * JDK-native Ed25519 (Java 15+) — no third-party crypto dependency. The upstream {@code com.standardwebhooks}
 * library is HMAC-only, so this scheme is implemented here directly against {@code java.security}.
 *
 * <p>Per the spec, the signed content for {@code v2a} is:
 *
 * <pre>endpoint_id.msg_id.timestamp.payload</pre>
 *
 * <p>and the signature is Ed25519 over the <strong>SHA-256 hash</strong> of that content (the raw 32 hash
 * bytes, not a hex/Base64 rendering of them):
 *
 * <pre>signature = base64( ed25519_sign(private_key, SHA-256("endpoint_id.msg_id.timestamp.payload")) )</pre>
 *
 * <p>The {@code endpoint_id} is prepended so that a single key pair can safely serve many endpoints: a
 * signature made for one endpoint fails verification at another, because the verifier rebuilds the content
 * with <em>its own</em> configured endpoint id. The endpoint id is never transmitted; both sides configure it.
 *
 * <p>Utility class — all methods static.
 */
public final class Ed25519Signatures {

    /** The {@code v2a} signature version identifier used in the {@code webhook-signature} header. */
    public static final String VERSION = "v2a";

    private Ed25519Signatures() {}

    /**
     * The spec's signed content: {@code endpoint_id.msg_id.timestamp.payload}.
     *
     * @param endpointId the stable, dot-free endpoint identifier shared by producer and consumer
     * @param messageId  the {@code webhook-id}
     * @param timestamp  the signing timestamp (epoch seconds)
     * @param payload    the raw JSON body exactly as sent
     */
    public static String signedContent(String endpointId, String messageId, long timestamp, String payload) {
        return endpointId + "." + messageId + "." + timestamp + "." + payload;
    }

    /**
     * Produce the bare (unversioned) Base64 {@code v2a} signature for the content.
     *
     * @return {@code base64(ed25519_sign(privateKey, SHA-256(content)))}
     */
    public static String sign(
            PrivateKey privateKey, String endpointId, String messageId, long timestamp, String payload) {
        try {
            byte[] hash = sha256(endpointId, messageId, timestamp, payload);
            Signature ed = Signature.getInstance("Ed25519");
            ed.initSign(privateKey);
            ed.update(hash);
            return Base64.getEncoder().encodeToString(ed.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute Ed25519 signature: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a bare (unversioned) Base64 {@code v2a} signature against the content.
     *
     * @param signatureBase64 the Base64 signature portion (the part after {@code v2a,})
     * @return {@code true} if the signature is valid for this public key, endpoint id, and content
     */
    public static boolean verify(
            PublicKey publicKey,
            String endpointId,
            String messageId,
            long timestamp,
            String payload,
            String signatureBase64) {
        return verifyAny(publicKey, endpointId, messageId, timestamp, payload, List.of(signatureBase64));
    }

    /**
     * Verify whether <em>any</em> bare (unversioned) Base64 {@code v2a} signature in {@code signaturesBase64}
     * matches the content, hashing the payload with SHA-256 <strong>at most once</strong> so multi-signature
     * headers cannot amplify payload allocation or digest work.
     *
     * @param signaturesBase64 candidate Base64 signatures (each after {@code v2a,})
     * @return {@code true} if any candidate signature is valid for this public key, endpoint id, and content
     */
    public static boolean verifyAny(
            PublicKey publicKey,
            String endpointId,
            String messageId,
            long timestamp,
            String payload,
            List<String> signaturesBase64) {
        if (signaturesBase64.isEmpty()) {
            return false;
        }
        try {
            byte[] hash = sha256(endpointId, messageId, timestamp, payload);
            Signature ed = Signature.getInstance("Ed25519");
            for (String signatureBase64 : signaturesBase64) {
                byte[] signatureBytes;
                try {
                    signatureBytes = Base64.getDecoder().decode(signatureBase64);
                } catch (IllegalArgumentException malformedBase64) {
                    continue;
                }
                try {
                    ed.initVerify(publicKey);
                    ed.update(hash);
                    if (ed.verify(signatureBytes)) {
                        return true;
                    }
                } catch (SignatureException invalidSignature) {
                    // Wrong-length or structurally invalid candidate signature; continue checking remaining ones.
                }
            }
            return false;
        } catch (GeneralSecurityException e) {
            // An unusable PublicKey verifies as false, not as an uncaught error.
            return false;
        }
    }

    /**
     * Incremental SHA-256 of {@code endpoint_id.msg_id.timestamp.payload} in UTF-8 without concatenating a
     * copy of the payload string. SHA-256 is a mandatory JDK algorithm, so {@code getInstance} never fails;
     * the declared {@link NoSuchAlgorithmException} is handled by each caller's existing catch.
     */
    private static byte[] sha256(String endpointId, String messageId, long timestamp, String payload)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte dot = (byte) '.';
        digest.update(endpointId.getBytes(StandardCharsets.UTF_8));
        digest.update(dot);
        digest.update(messageId.getBytes(StandardCharsets.UTF_8));
        digest.update(dot);
        digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
        digest.update(dot);
        return digest.digest(payload.getBytes(StandardCharsets.UTF_8));
    }
}
