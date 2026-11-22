package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.support.Signing;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigSecretProviderTest {

    private final ConfigSecretProvider provider = new ConfigSecretProvider();

    @Test
    void returnsPrimaryThenAdditionalSecretsDroppingBlanks() {
        Endpoint endpoint = new Endpoint();
        endpoint.setSecret(Signing.SECRET);
        endpoint.setAdditionalSecrets(List.of("whsec_OLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOL", "  "));

        assertThat(provider.secretsFor(endpoint))
                .containsExactly(Signing.SECRET, "whsec_OLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOL");
    }

    @Test
    void emptyWhenNoSecretConfigured() {
        Endpoint endpoint = new Endpoint();
        endpoint.setSecret(null);
        assertThat(provider.secretsFor(endpoint)).isEmpty();
    }
}
