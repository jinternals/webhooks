package com.jinternals.webhooks.producer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.secret.ConfigDestinationSecretProvider;
import com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.AllowListDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DestinationValidator} (SPEC.md §19d): every configured destination is checked at
 * startup for a parseable URL with a host that passes the active URL guard and at least one signing secret;
 * a problem fails the context at boot instead of surfacing on the first delivery.
 */
class DestinationValidatorTest {

    private final DestinationUrlValidator allowAll = new AllowAllDestinationUrlValidator();
    private final ConfigDestinationSecretProvider secretProvider = new ConfigDestinationSecretProvider();

    private static final String HMAC_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String PRIVATE_KEY = "whsk_4uKPjji/dhB8w6GW3YYDnIMYvXcdHrvH6lidTr4Z+EY=";
    private static final String PUBLIC_KEY = "whpk_uWKK13PfwqyOgrqT0OrWKcS2uVD7e89+oUKnkoPjx7w=";

    private WebhookProducerProperties propertiesWith(String name, String url, String secret) {
        return propertiesWith(name, url, secret, null);
    }

    private WebhookProducerProperties propertiesWith(String name, String url, String secret, String endpointId) {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        Destination destination = new Destination();
        destination.setUrl(url);
        destination.setSecret(secret);
        destination.setEndpointId(endpointId);
        properties.getDestinations().put(name, destination);
        return properties;
    }

    private DestinationValidator validator(WebhookProducerProperties properties, DestinationUrlValidator urlValidator) {
        return new DestinationValidator(properties, urlValidator, secretProvider);
    }

    @Test
    void acceptsWellFormedHttpsAndHttpDestinations() {
        WebhookProducerProperties httpsProps = propertiesWith("crm", "https://crm.example.com/webhooks", HMAC_SECRET);
        WebhookProducerProperties httpProps = propertiesWith("crm", "http://localhost:8080/webhooks", HMAC_SECRET);

        assertThatCode(() -> validator(httpsProps, allowAll).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator(httpProps, allowAll).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsADestinationWithUnsupportedOrMissingScheme() {
        WebhookProducerProperties ftpProps =
                propertiesWith("crm", "ftp://alice:s3cr3t@crm.example.com/webhooks?token=topsecret", HMAC_SECRET);

        assertThatThrownBy(() -> validator(ftpProps, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Unsupported URL scheme")
                .hasMessageContaining("crm")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("topsecret")
                .satisfies(e -> assertThat(((WebhookConfigurationException) e).getAction())
                        .contains("webhooks.producer.destinations.crm.url"));

        WebhookProducerProperties schemelessProps = propertiesWith("crm", "//crm.example.com/webhooks", HMAC_SECRET);

        assertThatThrownBy(() -> validator(schemelessProps, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Unsupported URL scheme");
    }

    @Test
    void rejectsADestinationWithNoUrl() {
        WebhookProducerProperties properties = propertiesWith("crm", null, HMAC_SECRET);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No URL configured")
                .hasMessageContaining("crm");
    }

    @Test
    void rejectsADestinationWithABlankUrl() {
        WebhookProducerProperties properties = propertiesWith("crm", "   ", HMAC_SECRET);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No URL configured");
    }

    @Test
    void rejectsADestinationWithASyntacticallyInvalidUrlAndRedactsEmbeddedSecrets() {
        String secretBearingBadUrl = "https://alice:s3cr3t@crm.example.com/webhooks?token=topsecret broken";
        WebhookProducerProperties properties = propertiesWith("crm", secretBearingBadUrl, HMAC_SECRET);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Invalid URL")
                .hasMessageContaining("crm")
                .hasMessageContaining("https://crm.example.com/webhooks")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("topsecret");
    }

    @Test
    void rejectsADestinationWhoseUrlHasNoHost() {
        // A URI with no authority/host (e.g. a bare path) cannot be delivered to.
        WebhookProducerProperties properties = propertiesWith("crm", "https:///tmp/webhooks", HMAC_SECRET);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("no host");
    }

    @Test
    void rejectsADestinationWithNoSecret() {
        WebhookProducerProperties properties = propertiesWith("crm", "https://crm.example.com/webhooks", null);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No signing secret configured")
                .hasMessageContaining("crm");
    }

    @Test
    void rejectsAMalformedSymmetricSecret() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", "whsec_not-valid-base64!!!");

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Invalid signing secret")
                .hasMessageContaining("crm")
                .satisfies(e -> assertThat(((WebhookConfigurationException) e).getAction())
                        .contains("webhooks.producer.destinations.crm.secret"));
    }

    @Test
    void rejectsASymmetricSecretShorterThan24Bytes() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", "whsec_c2hvcnRrZXk="); // 8 bytes

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("between 24 and 64 bytes");
    }

    @Test
    void skipsSecretValidationButStillValidatesUrlWhenSigningDisabled() {
        // With signing disabled (NoOpWebhookSigner), destinations need no secret — but MUST still have a valid URL.
        WebhookProducerProperties secretless = propertiesWith("crm", "https://crm.example.com/webhooks", null);
        secretless.setSigningEnabled(false);

        assertThatCode(() -> validator(secretless, allowAll).afterPropertiesSet())
                .doesNotThrowAnyException();

        WebhookProducerProperties urlless = propertiesWith("crm", null, null);
        urlless.setSigningEnabled(false);

        assertThatThrownBy(() -> validator(urlless, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No URL configured");
    }

    @Test
    void rejectsADestinationWhoseHostIsNotAllowListedAtBoot() {
        // The active URL guard must run at startup: a destination outside the allow-list fails at boot, not
        // on the first delivery. Proves validateUrl delegates to urlValidator.validate(url).
        WebhookProducerProperties properties = propertiesWith("crm", "https://evil.example.com/webhooks", HMAC_SECRET);
        DestinationUrlValidator allowList = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatThrownBy(() -> validator(properties, allowList).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("rejected by the destination URL guard")
                .hasMessageContaining("not allowed")
                .satisfies(e -> assertThat(((WebhookConfigurationException) e).getAction())
                        .contains("allowed-hosts"));
    }

    @Test
    void acceptsAnAllowListedHostAtBoot() {
        WebhookProducerProperties properties = propertiesWith("crm", "https://crm.example.com/webhooks", HMAC_SECRET);
        DestinationUrlValidator allowList = new AllowListDestinationUrlValidator(List.of("crm.example.com"));

        assertThatCode(() -> validator(properties, allowList).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void skipsAllValidationWhenDisabled() {
        // With validation disabled, even an obviously broken destination must NOT fail the context.
        WebhookProducerProperties properties = propertiesWith("crm", null, null);
        properties.setValidateDestinationsOnStartup(false);

        assertThatCode(() -> validator(properties, allowAll).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    // ---- v2a (whsk_) asymmetric signing rules (SPEC §19i) ----

    @Test
    void acceptsAV2aDestinationWithAWellFormedPrivateKeyAndEndpointId() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", PRIVATE_KEY, "ep_crm");

        try (var logs = com.jinternals.webhooks.producer.support.LogCapture.forClass(DestinationValidator.class)) {
            assertThatCode(() -> validator(properties, allowAll).afterPropertiesSet())
                    .doesNotThrowAnyException();
            // A legitimate v2a destination (endpoint-id + whsk_ key) must NOT trigger the "ignored" warning —
            // that warning is only for an endpoint-id set on a purely v1 target. A mutant forcing the branch
            // always-on would warn here and fail this.
            assertThat(logs.formattedMessages()).noneMatch(m -> m.contains("will be ignored"));
        }
    }

    @Test
    void rejectsAV2aDestinationWithNoEndpointId() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", PRIVATE_KEY, null);

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("endpoint-id is required")
                .satisfies(e -> assertThat(((WebhookConfigurationException) e).getAction())
                        .contains("webhooks.producer.destinations.crm.endpoint-id"));
    }

    @Test
    void rejectsAV2aDestinationWhoseEndpointIdContainsADot() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", PRIVATE_KEY, "ep.bad");

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("must not contain '.'");
    }

    @Test
    void rejectsAProducerConfiguredWithAPublicKey() {
        // A producer signs with the private key; a whpk_ public key is the consumer's half and must be
        // rejected at boot with a clear remediation, not silently fail at sign time.
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", PUBLIC_KEY, "ep_crm");

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("public key (whpk_)")
                .satisfies(e -> assertThat(((WebhookConfigurationException) e).getAction())
                        .contains("whsk_ private key"));
    }

    @Test
    void rejectsAMalformedPrivateKey() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", "whsk_not valid base64!!!", "ep_crm");

        assertThatThrownBy(() -> validator(properties, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("Invalid Ed25519 whsk_ private key");
    }

    @Test
    void warnsWhenAnEndpointIdIsSetOnAV1OnlyDestination() {
        // endpoint-id has no effect on a purely symmetric (whsec_, v1) destination; the validator must accept
        // it but warn, so a misplaced setting is noticed without failing the context.
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", HMAC_SECRET, "ep_crm");

        try (var logs = com.jinternals.webhooks.producer.support.LogCapture.forClass(DestinationValidator.class)) {
            assertThatCode(() -> validator(properties, allowAll).afterPropertiesSet())
                    .doesNotThrowAnyException();
            assertThat(logs.formattedMessages())
                    .anyMatch(m -> m.contains("endpoint-id is set") && m.contains("will be ignored"));
        }
    }

    @Test
    void rejectsDestinationWhenSecretProviderReturnsNull() {
        WebhookProducerProperties properties =
                propertiesWith("crm", "https://crm.example.com/webhooks", HMAC_SECRET, null);
        DestinationValidator nullReturningValidator = new DestinationValidator(properties, allowAll, d -> null);

        assertThatThrownBy(nullReturningValidator::afterPropertiesSet)
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No signing secret configured");

        WebhookProducerProperties nullEntryProps = new WebhookProducerProperties();
        nullEntryProps.getDestinations().put("broken", null);
        assertThatThrownBy(() -> validator(nullEntryProps, allowAll).afterPropertiesSet())
                .isInstanceOf(WebhookConfigurationException.class)
                .hasMessageContaining("No URL configured for webhook destination 'broken'");
    }
}
