package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.support.Signing;
import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import com.jinternals.webhooks.core.secret.SecretProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretValidatorTest {

    private final SecretProvider<Endpoint> provider = new ConfigSecretProvider();

    private WebhookProperties propsWith(Endpoint... endpoints) {
        WebhookProperties props = new WebhookProperties();
        props.getConsumer().setEndpoints(List.of(endpoints));
        return props;
    }

    private Endpoint endpoint(String path, String secret) {
        return endpoint(path, secret, null);
    }

    private Endpoint endpoint(String path, String secret, String endpointId) {
        Endpoint e = new Endpoint();
        e.setPath(path);
        e.setSecret(secret);
        e.setEndpointId(endpointId);
        return e;
    }

    @Test
    void passesForValidSecrets() {
        WebhookProperties props = propsWith(endpoint("/w", Signing.SECRET));
        assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenSecretMissing() {
        WebhookProperties props = propsWith(endpoint("/w", null));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No webhook secret");
    }

    @Test
    void failsFastWhenSecretMalformed() {
        // non-empty but lacking whsec_ prefix / invalid base64 / wrong key length -> rejected at startup
        WebhookProperties props = propsWith(endpoint("/w", "not-a-valid-secret!!!"));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Invalid webhook secret");
    }

    @Test
    void failsFastWhenSymmetricSecretIsShorterThan24Bytes() {
        WebhookProperties props = propsWith(endpoint("/w", "whsec_c2hvcnRrZXk=")); // "shortkey" = 8 bytes
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("between 24 and 64 bytes");
    }

    @Test
    void skipsValidationWhenDisabled() {
        WebhookProperties props = propsWith(endpoint("/w", null));
        props.getConsumer().setValidateSecretsOnStartup(false);
        assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void skipsValidationWhenSignatureVerificationDisabled() {
        // With verification off, endpoints need no secret — startup validation must not fail a secret-less
        // endpoint. A mutant dropping this skip would throw "No webhook secret" here.
        WebhookProperties props = propsWith(endpoint("/w", null));
        props.getConsumer().setSignatureVerificationEnabled(false);
        assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    // ---- v2a (whpk_) asymmetric verification rules (SPEC §8e) ----

    @Test
    void passesForAWellFormedV2aEndpoint() {
        WebhookProperties props = propsWith(endpoint("/w", Signing.PUBLIC_KEY, Signing.ENDPOINT_ID));
        assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenAV2aEndpointHasNoEndpointId() {
        WebhookProperties props = propsWith(endpoint("/w", Signing.PUBLIC_KEY, null));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("endpoint-id is required");
    }

    @Test
    void failsFastWhenAV2aEndpointIdContainsADot() {
        WebhookProperties props = propsWith(endpoint("/w", Signing.PUBLIC_KEY, "ep.bad"));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("must not contain '.'");
    }

    @Test
    void failsFastWhenAConsumerIsConfiguredWithAPrivateKey() {
        // A consumer verifies with the public key; a whsk_ private key is the producer's half and must be
        // rejected at boot with a clear remediation.
        WebhookProperties props = propsWith(endpoint("/w", Signing.PRIVATE_KEY, Signing.ENDPOINT_ID));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("private key (whsk_)");
    }

    @Test
    void failsFastWhenAPublicKeyIsMalformed() {
        WebhookProperties props = propsWith(endpoint("/w", "whpk_not valid base64!!!", Signing.ENDPOINT_ID));
        assertThatThrownBy(() -> new SecretValidator(props, provider).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Invalid Ed25519 whpk_ public key");
    }

    @Test
    void warnsWhenAnEndpointIdIsSetOnAV1OnlyEndpoint() {
        // endpoint-id has no effect on a purely symmetric (whsec_, v1) endpoint; validation must accept it
        // but warn, so a misplaced setting is noticed without failing the context.
        WebhookProperties props = propsWith(endpoint("/w", Signing.SECRET, "ep_crm"));

        try (var logs = com.jinternals.webhooks.consumer.support.LogCapture.forClass(SecretValidator.class)) {
            assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                    .doesNotThrowAnyException();
            assertThat(logs.formattedMessages())
                    .anyMatch(m -> m.contains("endpoint-id is set") && m.contains("will be ignored"));
        }
    }

    @Test
    void doesNotWarnForALegitimateV2aEndpoint() {
        // A valid v2a endpoint (endpoint-id + whpk_ key) must NOT trigger the "ignored" warning — that is
        // only for an endpoint-id set on a purely v1 endpoint. A mutant forcing the branch always-on fails.
        WebhookProperties props = propsWith(endpoint("/w", Signing.PUBLIC_KEY, Signing.ENDPOINT_ID));

        try (var logs = com.jinternals.webhooks.consumer.support.LogCapture.forClass(SecretValidator.class)) {
            assertThatCode(() -> new SecretValidator(props, provider).afterPropertiesSet())
                    .doesNotThrowAnyException();
            assertThat(logs.formattedMessages()).noneMatch(m -> m.contains("will be ignored"));
        }
    }
}
