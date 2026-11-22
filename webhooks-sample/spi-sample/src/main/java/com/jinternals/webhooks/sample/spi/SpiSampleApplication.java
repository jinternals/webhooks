package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataContributor;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Standalone sample demonstrating custom SPI bean overrides from {@code docs/reference/beans-and-spis.md},
 * with both {@code webhooks-consumer-starter} and {@code webhooks-producer-starter} coexisting in one
 * application context.
 */
@SpringBootApplication
@EnableWebhookClients(clients = SpiSampleApplication.CrmWebhooks.class)
public class SpiSampleApplication {

    private static final Logger log = LoggerFactory.getLogger(SpiSampleApplication.class);

    /** Demo secret resolved from a simulated secrets manager (never hardcoded in application.yaml!). */
    static final String MANAGED_ROTATING_NEW = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    static final String MANAGED_ROTATING_OLD = "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    public static void main(String[] args) {
        SpringApplication.run(SpiSampleApplication.class, args);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @WebhookType("contact.created")
    public static class ContactCreated {
        private String id;
        private String fullName;

        @Redact
        private String email;
    }

    @WebhookClient(destination = "crm")
    public interface CrmWebhooks {
        void contactCreated(ContactCreated event);
    }

    /** Consumer {@code SecretProvider<Endpoint>} resolving current + previous secrets dynamically. */
    @Bean
    SecretProvider<WebhookProperties.Endpoint> endpointSecretProvider() {
        Map<String, List<String>> vault = Map.of("/webhooks/crm", List.of(MANAGED_ROTATING_NEW, MANAGED_ROTATING_OLD));
        return endpoint -> vault.getOrDefault(endpoint.getPath(), List.of());
    }

    /**
     * Producer {@code SecretProvider<Destination>} — MUST be named {@code "destinationSecretProvider"} so it
     * overrides {@code ConfigDestinationSecretProvider} without colliding with the consumer's
     * {@code SecretProvider<Endpoint>}.
     */
    @Bean("destinationSecretProvider")
    SecretProvider<WebhookProducerProperties.Destination> destinationSecretProvider() {
        return destination -> List.of(MANAGED_ROTATING_NEW, MANAGED_ROTATING_OLD);
    }

    /** Shared reserve-then-commit {@link IdempotencyGuard}. */
    @Bean
    IdempotencyGuard sharedStoreIdempotencyGuard() {
        return new SharedStoreIdempotencyGuard(new SharedStoreIdempotencyGuard.InMemoryKeyValueStore());
    }

    /** Custom SSRF {@link DestinationUrlValidator} blocking link-local / cloud metadata & RFC-1918 IPs. */
    @Bean
    DestinationUrlValidator privateIpBlockingUrlValidator() {
        return new PrivateIpBlockingUrlValidator(true);
    }

    /** Additive {@link MetadataContributor} copying {@code x-tenant-id} into {@code metadata.attributes()}. */
    @Bean
    MetadataContributor tenantMetadataContributor() {
        return (request, headers, attributes) -> {
            String tenant = headers.get("x-tenant-id");
            if (tenant != null) {
                attributes.put("tenantId", tenant);
            }
        };
    }

    @Component
    public static class RecordedHooks implements HandlerFailureListener, WebhookDeliveryListener {
        private final List<DeliveryFailure> deliveryFailures = new CopyOnWriteArrayList<>();
        private final List<String> asyncHandlerFailures = new CopyOnWriteArrayList<>();

        @Override
        public void onDeliveryFailed(DeliveryFailure failure) {
            log.warn("Captured abandoned delivery url={} reason={}", failure.url(), failure.reason());
            deliveryFailures.add(failure);
        }

        @Override
        public void onHandlerFailed(String type, Throwable cause) {
            asyncHandlerFailures.add("failed:" + type);
        }

        @Override
        public void onHandlerTimeout(String type) {
            asyncHandlerFailures.add("timeout:" + type);
        }

        public List<DeliveryFailure> deliveryFailures() {
            return List.copyOf(deliveryFailures);
        }

        public List<String> asyncHandlerFailures() {
            return List.copyOf(asyncHandlerFailures);
        }
    }

    @Component
    public static class ContactHandlers {
        private final List<WebhookMetadata> received = new CopyOnWriteArrayList<>();

        @WebhookHandler
        public void onCreated(ContactCreated event, WebhookMetadata metadata) {
            received.add(metadata);
            log.info("Handled {} via custom SPIs: {}", metadata.webhookId(), metadata.redactedEvent());
        }

        public List<WebhookMetadata> received() {
            return List.copyOf(received);
        }

        public void clear() {
            received.clear();
        }
    }
}
