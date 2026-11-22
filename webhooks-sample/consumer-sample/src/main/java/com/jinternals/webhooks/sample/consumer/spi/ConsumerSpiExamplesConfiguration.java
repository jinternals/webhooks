package com.jinternals.webhooks.sample.consumer.spi;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.core.secret.SecretProvider;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Opt-in sample configuration ({@code --spring.profiles.active=spi-examples}) demonstrating how custom
 * consumer SPI {@code @Bean}s replace the library's {@code @ConditionalOnMissingBean} defaults (see
 * {@code docs/reference/beans-and-spis.md}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("spi-examples")
public class ConsumerSpiExamplesConfiguration {

    @Bean
    SharedStoreIdempotencyGuard.InMemoryKeyValueStore sampleKeyValueStore() {
        return new SharedStoreIdempotencyGuard.InMemoryKeyValueStore();
    }

    /** Replaces the default {@code NoOpIdempotencyGuard} / {@code InMemoryIdempotencyGuard}. */
    @Bean
    IdempotencyGuard sharedStoreIdempotencyGuard(SharedStoreIdempotencyGuard.InMemoryKeyValueStore store) {
        return new SharedStoreIdempotencyGuard(store);
    }

    /**
     * Replaces the default {@code ConfigSecretProvider} to resolve endpoint verification secrets dynamically
     * (e.g. from Vault or AWS/GCP Secrets Manager). Falls back to the endpoint's configured secret here so
     * existing sample endpoints remain valid.
     */
    @Bean
    SecretProvider<WebhookProperties.Endpoint> secretProvider() {
        Map<String, List<String>> managedSecrets =
                Map.of("/webhooks/crm", List.of("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"));
        return endpoint -> {
            List<String> resolved = managedSecrets.get(endpoint.getPath());
            if (resolved != null) {
                return resolved;
            }
            return endpoint.getSecret() != null ? List.of(endpoint.getSecret()) : List.of();
        };
    }
}
