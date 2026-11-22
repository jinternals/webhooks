package com.jinternals.webhooks.sample.producer.spi;

import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Opt-in sample configurations demonstrating how custom producer SPI {@code @Bean}s wire into or replace the
 * shipped defaults (see {@code docs/reference/beans-and-spis.md}):
 *
 * <ul>
 *   <li>{@code spi-examples} — adds a {@link BearerTokenWebhookInterceptor} and overrides
 *       {@code @Bean("destinationSecretProvider")} {@link SecretProvider};</li>
 *   <li>{@code queue-transport} — replaces {@link WebhookTransport} with {@link QueueingWebhookTransport}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ProducerSpiExamplesConfiguration {

    @Bean
    @Profile("spi-examples")
    WebhookRequestInterceptor bearerTokenWebhookInterceptor() {
        return new BearerTokenWebhookInterceptor(() -> "demo-access-token");
    }

    /**
     * Note the required bean name {@code "destinationSecretProvider"}: the producer auto-configuration keys
     * {@code @ConditionalOnMissingBean(name = "destinationSecretProvider")} and {@code @Qualifier} by name so
     * it never collides when consumer + producer share one context.
     */
    @Bean("destinationSecretProvider")
    @Profile("spi-examples")
    SecretProvider<WebhookProducerProperties.Destination> destinationSecretProvider() {
        return destination -> List.of(destination.getSecret());
    }

    @Bean
    @Profile("queue-transport")
    QueueingWebhookTransport queueingWebhookTransport() {
        return new QueueingWebhookTransport();
    }
}
