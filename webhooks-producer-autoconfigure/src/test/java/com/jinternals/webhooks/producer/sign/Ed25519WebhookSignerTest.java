package com.jinternals.webhooks.producer.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.Ed25519Signatures;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Ed25519WebhookSigner} (the producer half of the {@code v2a} scheme, SPEC.md §19i). It
 * emits a {@code v2a,<base64>} header value computed by the core primitive, requires an endpoint id, and
 * reports itself enabled. The centrepiece is the spec's worked-example known-answer vector, which proves the
 * producer is interoperable with any conforming receiver.
 */
class Ed25519WebhookSignerTest {

    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";
    private static final String ENDPOINT_ID = "ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb";
    private static final String MSG_ID = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
    private static final long TIMESTAMP = 1674087231L;
    private static final String PAYLOAD = "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344522Z\","
            + "\"data\":{\"id\":\"1f81eb52-5198-4599-803e-771906343485\"}}";
    private static final String SPEC_SIGNATURE =
            "kG9XaxZjAEpKB8KeQQb5G1Xp73YH5Rf5gxoWe7cg1kYMiyspdtZXe9w+dAwKeOurAwxfqmlOG/QR4m9s37DzAg==";

    private final Ed25519WebhookSigner signer = new Ed25519WebhookSigner();

    @Test
    void producesTheSpecWorkedExampleHeaderValue() {
        // The whole header value, version prefix included, must match the spec's published signature so a
        // conforming receiver verifies it unchanged.
        String header = signer.sign(PRIVATE_KEY, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD);
        assertThat(header).isEqualTo("v2a," + SPEC_SIGNATURE);
    }

    @Test
    void producesASignatureThatVerifiesWithTheMatchingPublicKey() {
        // Round-trip against the core verifier with the matching public key, as a real receiver would.
        String header = signer.sign(PRIVATE_KEY, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD);
        String bareSignature = header.substring("v2a,".length());
        PublicKey publicKey = Ed25519Keys.decodePublicKey(PUBLIC_KEY);
        assertThat(Ed25519Signatures.verify(publicKey, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD, bareSignature))
                .isTrue();
    }

    @Test
    void reportsSigningEnabled() {
        // isEnabled() true is what makes the publisher attach the signature header and require a secret.
        assertThat(signer.isEnabled()).isTrue();
    }

    @Test
    void rejectsANullSecret() {
        assertThatThrownBy(() -> signer.sign(null, ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No signing secret");
    }

    @Test
    void rejectsABlankSecret() {
        assertThatThrownBy(() -> signer.sign("   ", ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No signing secret");
    }

    @Test
    void rejectsAMissingEndpointId() {
        // v2a cannot sign without the endpoint id that is part of the signed content; a mutant dropping this
        // guard would sign "null.msg.ts.payload" and silently break endpoint isolation.
        assertThatThrownBy(() -> signer.sign(PRIVATE_KEY, null, MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void rejectsABlankEndpointId() {
        assertThatThrownBy(() -> signer.sign(PRIVATE_KEY, "  ", MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void rejectsAMalformedPrivateKey() {
        assertThatThrownBy(() -> signer.sign("whsk_not valid base64!!!", ENDPOINT_ID, MSG_ID, TIMESTAMP, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
