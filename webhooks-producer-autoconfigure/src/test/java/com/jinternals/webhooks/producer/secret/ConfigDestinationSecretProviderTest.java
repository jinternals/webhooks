package com.jinternals.webhooks.producer.secret;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigDestinationSecretProviderTest {

    private final ConfigDestinationSecretProvider provider = new ConfigDestinationSecretProvider();

    private static Destination destinationWithSecret(String secret) {
        Destination destination = new Destination();
        destination.setSecret(secret);
        return destination;
    }

    @Test
    void returnsConfiguredSecret() {
        assertThat(provider.secretsFor(destinationWithSecret("whsec_abc"))).containsExactly("whsec_abc");
    }

    @Test
    void returnsPrimaryThenAdditionalSecretsDroppingBlanks() {
        Destination destination = destinationWithSecret("whsec_new");
        destination.setAdditionalSecrets(List.of("whsec_old", "  "));

        assertThat(provider.secretsFor(destination)).containsExactly("whsec_new", "whsec_old");
    }

    @Test
    void emptyWhenSecretNull() {
        assertThat(provider.secretsFor(destinationWithSecret(null))).isEmpty();
    }

    @Test
    void emptyWhenSecretBlank() {
        assertThat(provider.secretsFor(destinationWithSecret("   "))).isEmpty();
    }
}
