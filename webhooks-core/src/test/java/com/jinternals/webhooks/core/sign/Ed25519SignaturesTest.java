package com.jinternals.webhooks.core.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Ed25519Signatures} (the {@code v2a} primitive, SPEC.md §8e / §19i).
 *
 * <p>The centrepiece is the <strong>known-answer test from the authoritative Standard Webhooks
 * specification's worked example</strong>: the exact key pair, endpoint id, message id, timestamp and payload
 * the spec publishes, together with the SHA-256 and signature it states. Matching those byte-for-byte is what
 * proves this implementation is interoperable with any conforming sender/receiver — a mutant that changed the
 * signed-content layout, the hash, or the signing would fail against the published vector.
 */
class Ed25519SignaturesTest {

    // ---- Authoritative worked example from the Standard Webhooks specification ----
    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";
    private static final String ENDPOINT_ID = "ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb";
    private static final String MSG_ID = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
    private static final long TIMESTAMP = 1674087231L;
    private static final String PAYLOAD = "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344522Z\","
            + "\"data\":{\"id\":\"1f81eb52-5198-4599-803e-771906343485\"}}";
    private static final String SPEC_SHA256_BASE64 = "YXCi5iN+rjB+iEb6bvg1XbWbVBL+r2S+as3k3n1Ih/A=";
    private static final String SPEC_SIGNATURE =
            "kG9XaxZjAEpKB8KeQQb5G1Xp73YH5Rf5gxoWe7cg1kYMiyspdtZXe9w+dAwKeOurAwxfqmlOG/QR4m9s37DzAg==";

    @Test
    void signedContentIsEndpointDotIdDotTimestampDotPayload() {
        // The signed content layout is the whole v2a contract; a mutant reordering or changing the delimiter
        // would break interoperability. Pin the exact string the spec hashes.
        assertThat(Ed25519Signatures.signedContent(ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD))
                .isEqualTo(ENDPOINT_ID + "." + MSG_ID + "." + TIMESTAMP + "." + PAYLOAD);
    }

    @Test
    void sha256OfSignedContentMatchesTheSpecWorkedExample() throws Exception {
        // The spec publishes the SHA-256 (base64) of the content; proving ours matches proves we hash exactly
        // the right bytes with exactly the right algorithm.
        String content = Ed25519Signatures.signedContent(ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        assertThat(Base64.getEncoder().encodeToString(hash)).isEqualTo(SPEC_SHA256_BASE64);
    }

    @Test
    void signProducesTheExactSignatureFromTheSpecWorkedExample() {
        // Ed25519 is deterministic, so the spec's published signature is a hard known-answer: our signature
        // over the spec's inputs must equal it byte-for-byte. This is the strongest interoperability check.
        PrivateKey privateKey = Ed25519Keys.decodePrivateKey(PRIVATE_KEY);
        String signature = Ed25519Signatures.sign(privateKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD);
        assertThat(signature).isEqualTo(SPEC_SIGNATURE);
    }

    @Test
    void verifyAcceptsTheSpecSignatureWithTheMatchingPublicKey() {
        // The published signature must verify against the published public key — closing the loop with the
        // other half of the key pair, exactly as a real receiver would.
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD, SPEC_SIGNATURE))
                .isTrue();
    }

    @Test
    void verifyAcceptsAFreshlyProducedSignature() {
        // Round-trip: sign then verify with the matching key over the same content.
        PrivateKey privateKey = Ed25519Keys.decodePrivateKey(PRIVATE_KEY);
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        String signature = Ed25519Signatures.sign(privateKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD);
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD, signature))
                .isTrue();
    }

    @Test
    void verifyRejectsAWebhookSignedForADifferentEndpointId() {
        // The endpoint id is the replay guard across endpoints sharing a key pair: changing it on the verify
        // side must fail even with the correct key and the genuine signature.
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(Ed25519Signatures.verify(publicKey, "ep_someone_else", MSG_ID, TIMESTAMP, PAYLOAD, SPEC_SIGNATURE))
                .isFalse();
    }

    @Test
    void verifyRejectsATamperedPayload() {
        // Any change to the signed content (here, the body) must invalidate the signature.
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD + " ", SPEC_SIGNATURE))
                .isFalse();
    }

    @Test
    void verifyRejectsAMalformedBase64Signature() {
        // A signature that is not valid Base64 is a non-match, never an error — the verifier must swallow the
        // decode failure and return false so a garbage header can't crash the receiver.
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD, "not base64!!!"))
                .isFalse();
    }

    @Test
    void verifyRejectsAWellFormedButWrongLengthSignature() {
        // A structurally invalid signature (valid Base64, wrong byte length) must verify as false, not throw —
        // the catch around Signature.verify is what makes a hostile header safe.
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        String shortSig = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD, shortSig))
                .isFalse();
    }

    @Test
    void signFailsLoudlyOnAnUnusableKey() {
        // sign() wraps any JCA failure in an IllegalStateException naming the problem (it is never a silent
        // non-event); a key the Ed25519 Signature cannot init with triggers that path. A mutant that dropped
        // the try/catch (letting the raw InvalidKeyException escape) would fail this.
        PrivateKey notEd25519 = new PrivateKey() {
            @Override
            public String getAlgorithm() {
                return "RSA";
            }

            @Override
            public String getFormat() {
                return "PKCS#8";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }
        };
        assertThatThrownBy(() -> Ed25519Signatures.sign(notEd25519, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to compute Ed25519 signature");
    }

    @Test
    void versionIdentifierIsV2a() {
        // The header version token must be exactly "v2a" so receivers can select the scheme.
        assertThat(Ed25519Signatures.VERSION).isEqualTo("v2a");
    }

    @Test
    void utilityClassIsNotInstantiable() throws Exception {
        var ctor = Ed25519Signatures.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(Ed25519Signatures.class);
    }
}
