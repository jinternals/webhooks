package com.jinternals.webhooks.core.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SymmetricKeysTest {

    private static String secretOfLength(int bytes) {
        byte[] raw = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            raw[i] = (byte) (i + 1);
        }
        return SymmetricKeys.SECRET_PREFIX + Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void decodesValidSecretAtExactMinimumBoundary24Bytes() {
        byte[] decoded = SymmetricKeys.decodeSecret(secretOfLength(24));
        assertThat(decoded).hasSize(24);
        assertThat(decoded[0]).isEqualTo((byte) 1);
        assertThat(decoded[23]).isEqualTo((byte) 24);
    }

    @Test
    void decodesValidSecretAtExactMaximumBoundary64Bytes() {
        byte[] decoded = SymmetricKeys.decodeSecret(secretOfLength(64));
        assertThat(decoded).hasSize(64);
        assertThat(decoded[63]).isEqualTo((byte) 64);
    }

    @Test
    void rejectsNullSecret() {
        assertThatThrownBy(() -> SymmetricKeys.decodeSecret(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be prefixed with 'whsec_'");
    }

    @Test
    void rejectsSecretWithoutWhsecPrefix() {
        String unprefixed = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatThrownBy(() -> SymmetricKeys.decodeSecret(unprefixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be prefixed with 'whsec_'");
    }

    @Test
    void rejectsMalformedBase64() {
        assertThatThrownBy(() -> SymmetricKeys.decodeSecret("whsec_not valid base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    void rejectsSecretOneByteBelowMinimum23Bytes() {
        assertThatThrownBy(() -> SymmetricKeys.decodeSecret(secretOfLength(23)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 24 and 64 bytes, got 23");
    }

    @Test
    void rejectsSecretOneByteAboveMaximum65Bytes() {
        assertThatThrownBy(() -> SymmetricKeys.decodeSecret(secretOfLength(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 24 and 64 bytes, got 65");
    }

    @Test
    void utilityClassIsNotInstantiable() throws Exception {
        var ctor = SymmetricKeys.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(SymmetricKeys.class);
    }
}
