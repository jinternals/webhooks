package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.support.Signing;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Ed25519WebhookVerifier} (the consumer half of the {@code v2a} scheme, SPEC.md §8e). It
 * verifies against a {@code whpk_} public key over {@code endpoint_id.msg_id.timestamp.payload}, requires the
 * endpoint id, supports a trust list of public keys, and delegates the replay window to the
 * {@link TimestampValidator}. The spec worked-example vector proves interoperability with a real sender.
 */
class Ed25519WebhookVerifierTest {

    // Signature-focused: window disabled so these tests pin the signature/endpoint-id logic, not freshness.
    private final Ed25519WebhookVerifier verifier = new Ed25519WebhookVerifier(new NoOpTimestampValidator());

    private static final String MSG_ID = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
    private static final long TIMESTAMP = 1674087231L;
    private static final String PAYLOAD = "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344522Z\","
            + "\"data\":{\"id\":\"1f81eb52-5198-4599-803e-771906343485\"}}";
    private static final String SPEC_HEADER =
            "v2a,kG9XaxZjAEpKB8KeQQb5G1Xp73YH5Rf5gxoWe7cg1kYMiyspdtZXe9w+dAwKeOurAwxfqmlOG/QR4m9s37DzAg==";

    private Map<String, List<String>> headers(String id, String ts, String sig) {
        return Map.of(
                WebhookHeaders.ID, List.of(id),
                WebhookHeaders.TIMESTAMP, List.of(ts),
                WebhookHeaders.SIGNATURE, List.of(sig));
    }

    @Test
    void verifiesTheSpecWorkedExampleSignature() {
        // The published signature must verify against the published public key with the published endpoint id —
        // end-to-end interoperability with a real Standard Webhooks sender.
        assertThatCode(() -> verifier.verify(
                        List.of(Signing.PUBLIC_KEY),
                        Signing.ENDPOINT_ID,
                        PAYLOAD,
                        headers(MSG_ID, Long.toString(TIMESTAMP), SPEC_HEADER)))
                .doesNotThrowAnyException();
    }

    @Test
    void verifiesAFreshlySignedWebhook() {
        String id = "msg_1";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() ->
                        verifier.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAgainstATrustListIfAnyPublicKeyVerifies() {
        // Rotation: a stale (non-matching) public key is listed first, then the real one. Any match accepts.
        String id = "msg_2";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String otherKey = "whpk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() -> verifier.verify(
                        List.of(otherKey, Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsWhenV1AndV2aSignaturesAreBothPresent() {
        // A dual-signed (migration) header carries a v1 and a v2a signature space-separated; the v2a verifier
        // must pick out and accept the v2a one, skipping the v1 part.
        String id = "msg_dual";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String v1 = Signing.sign(Signing.SECRET, id, ts, body);
        String v2a = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() -> verifier.verify(
                        List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, v1 + " " + v2a)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAWebhookSignedForADifferentEndpointId() {
        // The endpoint id is rebuilt from configuration, never the request: verifying with a different one
        // must fail even with the correct key and a genuine signature.
        String id = "msg_3";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.PUBLIC_KEY), "ep_other", body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));
    }

    @Test
    void rejectsATamperedBody() {
        String id = "msg_4";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatThrownBy(() -> verifier.verify(
                        List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body + " ", headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationFailure.class);
    }

    @Test
    void rejectsAHeaderWithOnlyAV1Signature() {
        // Given only a v1 signature, the v2a verifier finds no v2a part to check and must reject (no match).
        String id = "msg_5";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String v1 = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatThrownBy(() ->
                        verifier.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, v1)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));
    }

    @Test
    void ignoresAGenuineSignatureCarriedUnderADifferentVersionToken() {
        // The version token is load-bearing: a part holding the REAL v2a signature bytes but labelled with a
        // different version (here "v3a,") must be skipped, not verified. A mutant that bypassed the
        // "v2a".equals(version) check would feed these genuine bytes to the Ed25519 verifier and wrongly
        // accept — so this pins that the version must match exactly.
        String id = "msg_ver";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String real = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);
        String mislabelled = "v3a," + real.substring("v2a,".length());

        assertThatThrownBy(() -> verifier.verify(
                        List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, mislabelled)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));
    }

    @Test
    void rejectsASignaturePartWithNoComma() {
        // A malformed part (no "v2a,<sig>" structure) must be skipped by the parts.length guard, not crash.
        String id = "msg_6";
        String ts = Signing.now();
        String body = "{\"a\":1}";

        assertThatThrownBy(() ->
                        verifier.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, "v2a")))
                .isInstanceOf(WebhookVerificationFailure.class);
    }

    @Test
    void failsWhenNoSecretIsConfigured() {
        assertThatThrownBy(() -> verifier.verify(List.of(), Signing.ENDPOINT_ID, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.NO_SECRET));
    }

    @Test
    void failsWhenSecretsIsNull() {
        assertThatThrownBy(() -> verifier.verify(null, Signing.ENDPOINT_ID, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class);
    }

    @Test
    void failsWhenEndpointIdIsMissing() {
        // v2a cannot verify without the endpoint id; a null/blank one is a configuration-shaped failure, not a
        // silent non-match. A mutant dropping this guard would reach the matcher with a null endpoint id.
        assertThatThrownBy(() -> verifier.verify(List.of(Signing.PUBLIC_KEY), null, "{}", headers("m", "1", "v2a,x")))
                .isInstanceOf(WebhookVerificationFailure.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void failsWhenEndpointIdIsBlank() {
        assertThatThrownBy(() -> verifier.verify(List.of(Signing.PUBLIC_KEY), "  ", "{}", headers("m", "1", "v2a,x")))
                .isInstanceOf(WebhookVerificationFailure.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void failsWhenRequiredHeadersAreMissing() {
        assertThatThrownBy(() -> verifier.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void delegatesTheReplayWindowToTheTimestampValidator() {
        // A validator that rejects every timestamp must make verify() fail at the freshness step, proving the
        // injected window is honoured for v2a exactly as for v1.
        Ed25519WebhookVerifier windowed = new Ed25519WebhookVerifier(ts -> {
            throw new com.standardwebhooks.exceptions.WebhookVerificationException("stale");
        });
        String id = "msg_7";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatThrownBy(() ->
                        windowed.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .isInstanceOf(com.standardwebhooks.exceptions.WebhookVerificationException.class)
                .hasMessageContaining("stale");
    }
}
