package com.jinternals.webhooks.core.sign;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Decodes the Standard Webhooks asymmetric ({@code v2a}) key serializations into JDK key objects (see
 * SPEC.md §8e / §19i). The spec serializes Ed25519 keys as the <strong>raw 32 bytes</strong>, Base64-encoded,
 * behind a scheme-identifying prefix:
 *
 * <ul>
 *   <li>{@value #PRIVATE_KEY_PREFIX} — the producer's Ed25519 <strong>private</strong> seed (signs);</li>
 *   <li>{@value #PUBLIC_KEY_PREFIX} — the consumer's Ed25519 <strong>public</strong> key (verifies).</li>
 * </ul>
 *
 * <p>The JDK's {@code KeyFactory} expects PKCS#8 (private) / X.509 (public) DER, not the bare 32-byte form,
 * so this wraps the raw bytes in the fixed DER prelude for Ed25519 before handing them to the factory. The
 * preludes are the constant ASN.1 headers for an Ed25519 {@code OneAsymmetricKey} / {@code SubjectPublicKeyInfo}
 * whose only variable part is the trailing key bytes — so prepending them to a validated 32-byte key yields a
 * well-formed encoding without a BouncyCastle dependency (JDK-native Ed25519, Java 15+).
 *
 * <p>Utility class — all methods static. Decoding a malformed key throws {@link IllegalArgumentException}
 * naming the problem, so a misconfiguration fails fast rather than surfacing as a silent non-match.
 */
public final class Ed25519Keys {

    /** Serialization prefix for an Ed25519 private (secret) key: {@code whsk_} + Base64(raw 32 bytes). */
    public static final String PRIVATE_KEY_PREFIX = "whsk_";

    /** Serialization prefix for an Ed25519 public key: {@code whpk_} + Base64(raw 32 bytes). */
    public static final String PUBLIC_KEY_PREFIX = "whpk_";

    /** Raw Ed25519 key length in bytes (both private seed and public key are 32 bytes). */
    private static final int RAW_KEY_LENGTH = 32;

    /**
     * Fixed DER prelude for a PKCS#8-encoded Ed25519 private key ({@code OneAsymmetricKey}, version 0,
     * algorithm id 1.3.101.112, 32-byte raw seed in an OCTET STRING). Prepended to the raw seed to form a
     * 48-byte PKCS#8 encoding the JDK {@code KeyFactory} accepts.
     */
    private static final byte[] PKCS8_ED25519_PRELUDE = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    /**
     * Fixed DER prelude for an X.509-encoded Ed25519 public key ({@code SubjectPublicKeyInfo}, algorithm id
     * 1.3.101.112, 32-byte raw key in a BIT STRING). Prepended to the raw key to form a 44-byte X.509
     * encoding the JDK {@code KeyFactory} accepts.
     */
    private static final byte[] X509_ED25519_PRELUDE = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Ed25519Keys() {}

    /** Whether a secret string is an Ed25519 private key ({@code whsk_}-prefixed). */
    public static boolean isPrivateKey(String secret) {
        return secret != null && secret.startsWith(PRIVATE_KEY_PREFIX);
    }

    /** Whether a secret string is an Ed25519 public key ({@code whpk_}-prefixed). */
    public static boolean isPublicKey(String secret) {
        return secret != null && secret.startsWith(PUBLIC_KEY_PREFIX);
    }

    /** Decode a {@code whsk_}-prefixed private key into a JDK {@link PrivateKey}. */
    public static PrivateKey decodePrivateKey(String serialized) {
        byte[] raw = rawKeyBytes(serialized, PRIVATE_KEY_PREFIX);
        return privateKeyFromPkcs8(concat(PKCS8_ED25519_PRELUDE, raw));
    }

    /** Decode a {@code whpk_}-prefixed public key into a JDK {@link PublicKey}. */
    public static PublicKey decodePublicKey(String serialized) {
        byte[] raw = rawKeyBytes(serialized, PUBLIC_KEY_PREFIX);
        return publicKeyFromX509(concat(X509_ED25519_PRELUDE, raw));
    }

    /**
     * Build a {@link PrivateKey} from a complete PKCS#8 DER encoding, converting any JCA failure into an
     * {@link IllegalArgumentException}. Package-private so a test can exercise the failure path with a
     * deliberately corrupt encoding — the public {@link #decodePrivateKey} validates the raw length first, so
     * the failure path is otherwise unreachable through it.
     */
    static PrivateKey privateKeyFromPkcs8(byte[] pkcs8) {
        try {
            return keyFactory().generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Ed25519 private key: " + e.getMessage(), e);
        }
    }

    /**
     * Build a {@link PublicKey} from a complete X.509 DER encoding, converting any JCA failure into an
     * {@link IllegalArgumentException}. Package-private for the same reason as {@link #privateKeyFromPkcs8}.
     */
    static PublicKey publicKeyFromX509(byte[] x509) {
        try {
            return keyFactory().generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key: " + e.getMessage(), e);
        }
    }

    /** Strip the prefix, Base64-decode, and check the 32-byte length. */
    private static byte[] rawKeyBytes(String serialized, String prefix) {
        if (serialized == null || !serialized.startsWith(prefix)) {
            throw new IllegalArgumentException("Ed25519 key must be prefixed with '" + prefix + "'");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(serialized.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ed25519 key is not valid Base64: " + e.getMessage(), e);
        }
        if (raw.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Ed25519 key must decode to " + RAW_KEY_LENGTH + " bytes, got " + raw.length);
        }
        return raw;
    }

    private static KeyFactory keyFactory() throws NoSuchAlgorithmException {
        return KeyFactory.getInstance("Ed25519");
    }

    private static byte[] concat(byte[] prefix, byte[] rest) {
        byte[] out = new byte[prefix.length + rest.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(rest, 0, out, prefix.length, rest.length);
        return out;
    }
}
