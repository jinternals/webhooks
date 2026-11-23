package com.jinternals.webhooks.core.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Ed25519Keys} (the {@code whsk_}/{@code whpk_} key serializations, SPEC.md §8e / §19i).
 * Confirms it decodes the spec's raw-32-byte Base64 form into working JDK keys, classifies by prefix, and
 * fails fast with a naming message on every malformed input — never a silent wrong-key non-match.
 */
class Ed25519KeysTest {

    // The spec's worked-example key pair (raw 32 bytes, Base64, prefixed).
    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";

    @Test
    void prefixesAreTheSpecSerializationIdentifiers() {
        assertThat(Ed25519Keys.PRIVATE_KEY_PREFIX).isEqualTo("whsk_");
        assertThat(Ed25519Keys.PUBLIC_KEY_PREFIX).isEqualTo("whpk_");
    }

    @Test
    void classifiesPrivateKeysByPrefix() {
        assertThat(Ed25519Keys.isPrivateKey(PRIVATE_KEY)).isTrue();
        assertThat(Ed25519Keys.isPrivateKey(PUBLIC_KEY)).isFalse();
        assertThat(Ed25519Keys.isPrivateKey("whsec_abc")).isFalse();
        assertThat(Ed25519Keys.isPrivateKey(null)).isFalse();
    }

    @Test
    void classifiesPublicKeysByPrefix() {
        assertThat(Ed25519Keys.isPublicKey(PUBLIC_KEY)).isTrue();
        assertThat(Ed25519Keys.isPublicKey(PRIVATE_KEY)).isFalse();
        assertThat(Ed25519Keys.isPublicKey("whsec_abc")).isFalse();
        assertThat(Ed25519Keys.isPublicKey(null)).isFalse();
    }

    @Test
    void decodesAWellFormedPrivateKeyIntoAWorkingSigningKey() {
        // The real contract is that the decoded key signs: pair it with the matching public key and round-trip
        // through the v2a primitive. (The JDK reports the algorithm as "EdDSA", so assert behaviour, not name.)
        PrivateKey privateKey = Ed25519Keys.decodePrivateKey(PRIVATE_KEY);
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        String signature = Ed25519Signatures.sign(privateKey, "ep_x", "msg_1", 1L, "{}");
        assertThat(Ed25519Signatures.verify(publicKey, "ep_x", "msg_1", 1L, "{}", signature))
                .isTrue();
    }

    @Test
    void decodesAWellFormedPublicKeyIntoAWorkingVerificationKey() {
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(publicKey).isNotNull();
        // A signature made by the matching private key verifies with this decoded public key.
        PrivateKey privateKey = Ed25519Keys.decodePrivateKey(PRIVATE_KEY);
        String signature = Ed25519Signatures.sign(privateKey, "ep_x", "msg_1", 1L, "{}");
        assertThat(Ed25519Signatures.verify(publicKey, "ep_x", "msg_1", 1L, "{}", signature))
                .isTrue();
    }

    @Test
    void rejectsAPrivateKeyWithoutTheWhskPrefix() {
        assertThatThrownBy(() -> Ed25519Keys.decodePrivateKey("whpk_oops"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whsk_");
    }

    @Test
    void rejectsANullPrivateKey() {
        assertThatThrownBy(() -> Ed25519Keys.decodePrivateKey(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whsk_");
    }

    @Test
    void rejectsAPublicKeyWithoutTheWhpkPrefix() {
        assertThatThrownBy(() -> Ed25519Keys.decodePublicKey("whsk_oops"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whpk_");
    }

    @Test
    void rejectsKeyMaterialThatIsNotValidBase64() {
        assertThatThrownBy(() -> Ed25519Keys.decodePrivateKey("whsk_not valid base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    void rejectsKeyMaterialOfTheWrongLength() {
        // Valid Base64 but not 32 bytes — a wrong-length key must be rejected by the length check, not
        // squeezed into a malformed DER encoding that fails cryptically later.
        String tooShort = Ed25519Keys.PRIVATE_KEY_PREFIX + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertThatThrownBy(() -> Ed25519Keys.decodePrivateKey(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void publicWrongLengthIsAlsoRejected() {
        String tooLong = Ed25519Keys.PUBLIC_KEY_PREFIX + Base64.getEncoder().encodeToString(new byte[33]);
        assertThatThrownBy(() -> Ed25519Keys.decodePublicKey(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void privateKeyBuildFailsOnCorruptDer() {
        // The public decodePrivateKey validates the 32-byte length first, so its JCA-failure path is otherwise
        // unreachable. Drive the conversion seam directly with a corrupt PKCS#8 encoding: a JCA failure must
        // surface as a naming IllegalArgumentException, not a raw InvalidKeySpecException.
        assertThatThrownBy(() -> Ed25519Keys.privateKeyFromPkcs8(new byte[] {0x00, 0x01, 0x02}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Ed25519 private key");
    }

    @Test
    void publicKeyBuildFailsOnCorruptDer() {
        assertThatThrownBy(() -> Ed25519Keys.publicKeyFromX509(new byte[] {0x00, 0x01, 0x02}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Ed25519 public key");
    }

    @Test
    void utilityClassIsNotInstantiable() throws Exception {
        // Ed25519Keys is a static-only utility; its private constructor exists solely to prevent instantiation.
        var ctor = Ed25519Keys.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(Ed25519Keys.class);
    }
}
