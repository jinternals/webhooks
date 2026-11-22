package com.jinternals.webhooks.producer.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookSigningException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HmacWebhookSignerTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    private final HmacWebhookSigner signer = new HmacWebhookSigner();

    /** Independent reference signature over {@code id.timestamp.payload}, matching the SW scheme. */
    private static String referenceSignature(String secret, String id, long ts, String body) {
        try {
            byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String toSign = id + "." + ts + "." + body;
            byte[] digest = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void isEnabledByDefault() {
        // The default signer reports enabled, so the publisher signs and emits a webhook-signature header.
        assertThat(signer.isEnabled()).isTrue();
    }

    @Test
    void signsPayloadMatchingStandardScheme() {
        String sig = signer.sign(SECRET, null, "msg_1", 1700000000L, "{\"a\":1}");
        assertThat(sig).isEqualTo(referenceSignature(SECRET, "msg_1", 1700000000L, "{\"a\":1}"));
    }

    @Test
    void signingIsDeterministicAcrossCalls() {
        // Same secret + inputs → identical signature on every call (a fresh Webhook per sign, no cache).
        String first = signer.sign(SECRET, null, "msg_1", 1700000000L, "{\"a\":1}");
        String second = signer.sign(SECRET, null, "msg_1", 1700000000L, "{\"a\":1}");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectsNullSecret() {
        assertThatThrownBy(() -> signer.sign(null, null, "msg_1", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No signing secret");
    }

    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> signer.sign("   ", null, "msg_1", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No signing secret");
    }

    @Test
    void rejectsMalformedSecret() {
        assertThatThrownBy(() -> signer.sign("whsec_not valid base64!!", null, "msg_1", 1L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid webhook secret");
    }

    @Test
    void rejectsSymmetricSecretOutsideSpecKeyLength() {
        assertThatThrownBy(() -> signer.sign("whsec_c2hvcnRrZXk=", null, "msg_1", 1L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 24 and 64 bytes");
    }

    @Test
    void rejectsInvalidMessageId() {
        assertThatThrownBy(() -> signer.sign(SECRET, null, null, 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message-id is required");
        assertThatThrownBy(() -> signer.sign(SECRET, null, "   ", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message-id is required");
        assertThatThrownBy(() -> signer.sign(SECRET, null, "msg.evil", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain '.'");
    }

    @Test
    void wrapsSigningExceptionAsIllegalState() {
        // A valid secret never fails to sign, but the library declares a checked exception we must
        // handle. Override the sign seam to force that path and assert it is wrapped.
        HmacWebhookSigner throwing = new HmacWebhookSigner() {
            @Override
            String doSign(Webhook webhook, String messageId, long timestamp, String payload)
                    throws WebhookSigningException {
                throw new WebhookSigningException("boom");
            }
        };
        assertThatThrownBy(() -> throwing.sign(SECRET, null, "msg_1", 1L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to sign webhook payload")
                .hasMessageContaining("boom");
    }
}
