package com.jinternals.webhooks.core.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SigningSchemeValidator} — the shared startup validation of {@code v2a} signing
 * material and the {@code endpoint-id} invariants (SPEC.md §8e / §19i), raised by both the producer's
 * {@code DestinationValidator} and the consumer's {@code SecretValidator}. Every failure must be a
 * {@link WebhookConfigurationException} with a usable description <em>and</em> a remediation action, and must
 * never contain the secret value.
 */
class SigningSchemeValidatorTest {

    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";
    private static final String PROP = "webhooks.producer.destinations.crm.endpoint-id";

    // ---- usesAsymmetric ----

    @Test
    void usesAsymmetricDetectsPrivateAndPublicKeys() {
        assertThat(SigningSchemeValidator.usesAsymmetric(List.of(PRIVATE_KEY))).isTrue();
        assertThat(SigningSchemeValidator.usesAsymmetric(List.of(PUBLIC_KEY))).isTrue();
        assertThat(SigningSchemeValidator.usesAsymmetric(List.of("whsec_abc", PUBLIC_KEY)))
                .isTrue();
    }

    @Test
    void usesAsymmetricIsFalseForOnlySymmetricSecrets() {
        assertThat(SigningSchemeValidator.usesAsymmetric(List.of("whsec_abc"))).isFalse();
        assertThat(SigningSchemeValidator.usesAsymmetric(List.of())).isFalse();
    }

    // ---- producer side (expectPrivate = true) ----

    @Test
    void acceptsAWellFormedPrivateKeyWithAValidEndpointId() {
        assertThatCode(() -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, "ep_abc", List.of(PRIVATE_KEY), true))
                .doesNotThrowAnyException();
    }

    @Test
    void symmetricOnlyTargetNeedsNoEndpointIdAndPasses() {
        assertThatCode(() -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, null, List.of("whsec_abc"), true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAsymmetricKeyWithoutAnEndpointId() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, null, List.of(PRIVATE_KEY), true),
                "endpoint-id is required",
                PROP);
    }

    @Test
    void rejectsABlankEndpointId() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, "   ", List.of(PRIVATE_KEY), true),
                "endpoint-id is required",
                PROP);
    }

    @Test
    void rejectsAnEndpointIdContainingADot() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, "ep.bad", List.of(PRIVATE_KEY), true),
                "must not contain '.'",
                PROP);
    }

    @Test
    void producerRejectsAPublicKeyWhereAPrivateKeyIsRequired() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, "ep_abc", List.of(PUBLIC_KEY), true),
                "public key (whpk_)",
                "whsk_ private key");
    }

    @Test
    void producerRejectsAMalformedPrivateKey() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "destination 'crm'", PROP, "ep_abc", List.of("whsk_not valid base64!!!"), true),
                "Invalid Ed25519 whsk_ private key",
                "raw");
    }

    // ---- consumer side (expectPrivate = false) ----

    @Test
    void acceptsAWellFormedPublicKeyWithAValidEndpointId() {
        assertThatCode(() -> SigningSchemeValidator.validateAsymmetric(
                        "endpoint '/webhooks/crm'", PROP, "ep_abc", List.of(PUBLIC_KEY), false))
                .doesNotThrowAnyException();
    }

    @Test
    void consumerRejectsAPrivateKeyWhereAPublicKeyIsRequired() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "endpoint '/webhooks/crm'", PROP, "ep_abc", List.of(PRIVATE_KEY), false),
                "private key (whsk_)",
                "whpk_ public key");
    }

    @Test
    void consumerRejectsAMalformedPublicKey() {
        assertConfigFailure(
                () -> SigningSchemeValidator.validateAsymmetric(
                        "endpoint '/webhooks/crm'", PROP, "ep_abc", List.of("whpk_not valid base64!!!"), false),
                "Invalid Ed25519 whpk_ public key",
                "raw");
    }

    // ---- isEndpointIdIgnored ----

    @Test
    void endpointIdIsIgnoredOnlyWhenSetWithNoAsymmetricKey() {
        assertThat(SigningSchemeValidator.isEndpointIdIgnored("ep_abc", List.of("whsec_abc")))
                .isTrue();
        assertThat(SigningSchemeValidator.isEndpointIdIgnored("ep_abc", List.of(PUBLIC_KEY)))
                .isFalse();
        assertThat(SigningSchemeValidator.isEndpointIdIgnored(null, List.of("whsec_abc")))
                .isFalse();
        assertThat(SigningSchemeValidator.isEndpointIdIgnored("  ", List.of("whsec_abc")))
                .isFalse();
    }

    @Test
    void utilityClassIsNotInstantiable() throws Exception {
        var ctor = SigningSchemeValidator.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(SigningSchemeValidator.class);
    }

    /** Assert the action throws a {@link WebhookConfigurationException} with a usable description + action. */
    private static void assertConfigFailure(
            ThrowingCallable action, String descriptionFragment, String actionFragment) {
        assertThatThrownBy(action)
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining(descriptionFragment)
                .satisfies(e -> {
                    WebhookConfigurationException wce = (WebhookConfigurationException) e;
                    assertThat(wce.getAction()).isNotBlank().contains(actionFragment);
                });
    }
}
