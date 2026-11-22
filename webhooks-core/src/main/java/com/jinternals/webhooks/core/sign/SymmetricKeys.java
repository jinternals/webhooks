package com.jinternals.webhooks.core.sign;

import java.util.Base64;

/**
 * Decodes and validates Standard Webhooks symmetric ({@code v1}, HMAC-SHA256) secrets against the official
 * specification:
 *
 * <ul>
 *   <li>Serialized with the {@value #SECRET_PREFIX} scheme-identifying prefix followed by Base64 key bytes;</li>
 *   <li>Decoded key length between {@value #MIN_KEY_LENGTH} bytes (192 bits) and {@value #MAX_KEY_LENGTH}
 *       bytes (512 bits) inclusive.</li>
 * </ul>
 *
 * <p>Utility class — all methods static. Decoding a malformed or out-of-range secret throws
 * {@link IllegalArgumentException} naming the structural problem (never echoing the secret value).
 */
public final class SymmetricKeys {

    /** Serialization prefix for a Standard Webhooks symmetric (HMAC-SHA256) secret. */
    public static final String SECRET_PREFIX = "whsec_";

    /** Minimum decoded HMAC key length in bytes per the Standard Webhooks specification (192 bits). */
    public static final int MIN_KEY_LENGTH = 24;

    /** Maximum decoded HMAC key length in bytes per the Standard Webhooks specification (512 bits). */
    public static final int MAX_KEY_LENGTH = 64;

    private SymmetricKeys() {}

    /**
     * Validate and decode a {@code whsec_}-prefixed symmetric secret into its raw key bytes.
     *
     * @param secret the serialized {@code whsec_<base64>} secret
     * @return the decoded HMAC key bytes ({@code 24..64} bytes)
     * @throws IllegalArgumentException if {@code secret} is null, lacks the {@code whsec_} prefix, is not
     *                                  valid Base64, or decodes outside {@code [24, 64]} bytes
     */
    public static byte[] decodeSecret(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException(
                    "Symmetric webhook secret must be prefixed with '" + SECRET_PREFIX + "'");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Symmetric webhook secret is not valid Base64: " + e.getMessage(), e);
        }
        if (raw.length < MIN_KEY_LENGTH || raw.length > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Symmetric webhook secret must decode to between "
                    + MIN_KEY_LENGTH
                    + " and "
                    + MAX_KEY_LENGTH
                    + " bytes, got "
                    + raw.length);
        }
        return raw;
    }
}
