package com.jinternals.webhooks.consumer.support;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.Ed25519Signatures;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Test helper that produces a valid Standard Webhooks signature for a body, so tests can exercise
 * the real verification path end-to-end (the symmetric signed content is {@code id.timestamp.payload}; the
 * asymmetric {@code v2a} content is {@code endpoint_id.id.timestamp.payload}).
 */
public final class Signing {

    /** A valid demo secret (base64 body after the whsec_ prefix). */
    public static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    /** The spec worked-example {@code v2a} key pair and endpoint id, for interoperable round-trips. */
    public static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";

    public static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";

    public static final String ENDPOINT_ID = "ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb";

    private Signing() {}

    /** Unix-seconds timestamp for "now". */
    public static String now() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    /** The {@code webhook-signature} header value ({@code v1,<base64 hmac>}) for the given inputs. */
    public static String sign(String secret, String id, String timestamp, String body) {
        try {
            byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String toSign = id + "." + timestamp + "." + body;
            byte[] digest = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code webhook-signature} header value ({@code v2a,<base64 ed25519>}) for the given inputs. */
    public static String signAsymmetric(
            String privateKey, String endpointId, String id, String timestamp, String body) {
        PrivateKey key = Ed25519Keys.decodePrivateKey(privateKey);
        String signature = Ed25519Signatures.sign(key, endpointId, id, Long.parseLong(timestamp), body);
        return Ed25519Signatures.VERSION + "," + signature;
    }
}
