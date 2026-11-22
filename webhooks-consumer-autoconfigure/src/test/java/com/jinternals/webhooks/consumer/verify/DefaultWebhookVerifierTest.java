package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.support.Signing;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DefaultWebhookVerifier}: the shipped default that selects the verification scheme
 * <strong>per secret, by prefix</strong> (SPEC.md §8e). A {@code whpk_} key verifies {@code v2a}, anything
 * else verifies {@code v1}, and the webhook is accepted if any secret verifies any signature — across
 * schemes, so a dual-signed migration delivery is accepted.
 */
class DefaultWebhookVerifierTest {

    private final DefaultWebhookVerifier verifier = new DefaultWebhookVerifier(new NoOpTimestampValidator());

    private Map<String, List<String>> headers(String id, String ts, String sig) {
        return Map.of(
                WebhookHeaders.ID, List.of(id),
                WebhookHeaders.TIMESTAMP, List.of(ts),
                WebhookHeaders.SIGNATURE, List.of(sig));
    }

    @Test
    void verifiesAV1OnlyWebhookWithAWhsecSecret() {
        String id = "msg_1";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatCode(() -> verifier.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void verifiesAV2aOnlyWebhookWithAWhpkKey() {
        String id = "msg_2";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() ->
                        verifier.verify(List.of(Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void verifiesAMixedTrustListRegardlessOfKeyOrder() {
        // An endpoint may configure both a whsec_ and a whpk_ secret during a scheme migration; a v2a-signed
        // delivery must be accepted even though the whsec_ key is tried first. This proves the per-secret
        // dispatch routes each secret independently.
        String id = "msg_3";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() -> verifier.verify(
                        List.of(Signing.SECRET, Signing.PUBLIC_KEY), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .doesNotThrowAnyException();
    }

    @Test
    void verifiesADualSignedWebhookViaTheV1Secret() {
        // Header carries both v1 and v2a; with only the whsec_ secret configured, the v1 part must verify.
        String id = "msg_4";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String v1 = Signing.sign(Signing.SECRET, id, ts, body);
        String v2a = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);

        assertThatCode(() -> verifier.verify(
                        List.of(Signing.SECRET), Signing.ENDPOINT_ID, body, headers(id, ts, v1 + " " + v2a)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenNoSecretVerifiesAnySignature() {
        String id = "msg_5";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        // Signed with the real key, but a wrong secret of each scheme is configured.
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, Signing.ENDPOINT_ID, id, ts, body);
        String wrongHmac = "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String wrongPub = "whpk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        assertThatThrownBy(() ->
                        verifier.verify(List.of(wrongHmac, wrongPub), Signing.ENDPOINT_ID, body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.SIGNATURE));
    }

    @Test
    void failsWhenNoSecretIsConfigured() {
        assertThatThrownBy(() -> verifier.verify(List.of(), null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.NO_SECRET));
    }

    @Test
    void failsWhenSecretsIsNull() {
        assertThatThrownBy(() -> verifier.verify(null, null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.NO_SECRET));
    }

    @Test
    void failsWhenRequiredHeadersAreMissing() {
        assertThatThrownBy(() -> verifier.verify(List.of(Signing.SECRET), null, "{}", Map.of()))
                .isInstanceOf(WebhookVerificationFailure.class)
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.MISSING_HEADERS));
    }

    @Test
    void delegatesTheReplayWindowToTheTimestampValidatorOnce() {
        DefaultWebhookVerifier windowed = new DefaultWebhookVerifier(ts -> {
            throw new com.standardwebhooks.exceptions.WebhookVerificationException("stale");
        });
        String id = "msg_6";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        assertThatThrownBy(() -> windowed.verify(List.of(Signing.SECRET), null, body, headers(id, ts, sig)))
                .isInstanceOf(com.standardwebhooks.exceptions.WebhookVerificationException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void failsClosedWhenAV2aKeyIsUsedWithoutAnEndpointId() {
        // Regression guard: DefaultWebhookVerifier calls ed25519.matches(...) per secret; if startup validation
        // was disabled or a dynamic SecretProvider returned a whpk_ key with no endpoint-id, it must fail closed
        // with NO_SECRET rather than verifying against "null.<msgId>...".
        String id = "msg_no_ep";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String forgedForLiteralNull = Signing.signAsymmetric(Signing.PRIVATE_KEY, "null", id, ts, body);

        assertThatThrownBy(() ->
                        verifier.verify(List.of(Signing.PUBLIC_KEY), null, body, headers(id, ts, forgedForLiteralNull)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .hasMessageContaining("endpoint-id is required")
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.NO_SECRET));
    }

    @Test
    void failsClosedWhenAV2aKeyIsUsedWithADottedEndpointId() {
        String id = "msg_dot_ep";
        String ts = Signing.now();
        String body = "{\"a\":1}";
        String sig = Signing.signAsymmetric(Signing.PRIVATE_KEY, "ep.bad", id, ts, body);

        assertThatThrownBy(() -> verifier.verify(List.of(Signing.PUBLIC_KEY), "ep.bad", body, headers(id, ts, sig)))
                .isInstanceOf(WebhookVerificationFailure.class)
                .hasMessageContaining("must not contain '.'")
                .satisfies(e -> assertThat(((WebhookVerificationFailure) e).reason())
                        .isEqualTo(VerificationFailureReason.NO_SECRET));
    }

    @Test
    void rejectsAProducerPrivateKeyConfiguredOnAConsumerEndpoint() {
        String id = "msg_priv";
        String ts = Signing.now();

        assertThatThrownBy(() -> verifier.verify(
                        List.of(Signing.PRIVATE_KEY), Signing.ENDPOINT_ID, "{}", headers(id, ts, "v2a,x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private key (whsk_)");
    }
}
