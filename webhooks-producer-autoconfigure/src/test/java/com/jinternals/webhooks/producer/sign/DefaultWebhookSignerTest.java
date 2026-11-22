package com.jinternals.webhooks.producer.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DefaultWebhookSigner}: the shipped default that selects the signing scheme
 * <strong>per secret, by prefix</strong> (SPEC.md §19i). A {@code whsk_} secret must produce a {@code v2a,…}
 * header and anything else a {@code v1,…} header, so a single rotation list can carry both schemes.
 */
class DefaultWebhookSignerTest {

    private static final String HMAC_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String ENDPOINT_ID = "ep_2fVZdC8Kx9TR4ZoCqHWt3NnLgRb";

    private final DefaultWebhookSigner signer = new DefaultWebhookSigner();

    @Test
    void routesAWhsecSecretToTheV1HmacScheme() {
        // A whsec_ secret must be signed symmetrically: the header is v1,... A mutant that routed it to the
        // Ed25519 engine would try to decode it as a private key and fail, or emit v2a — either is caught here.
        String header = signer.sign(HMAC_SECRET, null, "msg_1", 1700000000L, "{\"a\":1}");
        assertThat(header).startsWith("v1,");
    }

    @Test
    void routesAWhskSecretToTheV2aEd25519Scheme() {
        // A whsk_ private key must be signed asymmetrically: the header is v2a,...
        String header = signer.sign(PRIVATE_KEY, ENDPOINT_ID, "msg_1", 1700000000L, "{\"a\":1}");
        assertThat(header).startsWith("v2a,");
    }

    @Test
    void v1RoutingIgnoresTheEndpointId() {
        // The endpoint id is a v2a-only concern; routing a v1 secret must not require or use it.
        String withEp = signer.sign(HMAC_SECRET, "ep_anything", "msg_1", 1700000000L, "{\"a\":1}");
        String withoutEp = signer.sign(HMAC_SECRET, null, "msg_1", 1700000000L, "{\"a\":1}");
        assertThat(withEp).isEqualTo(withoutEp);
    }

    @Test
    void v2aRoutingStillRequiresTheEndpointId() {
        // Routing to v2a must carry the endpoint-id requirement through — a whsk_ secret with no endpoint id
        // fails exactly as the Ed25519 signer demands.
        assertThatThrownBy(() -> signer.sign(PRIVATE_KEY, null, "msg_1", 1700000000L, "{\"a\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void reportsSigningEnabled() {
        assertThat(signer.isEnabled()).isTrue();
    }
}
