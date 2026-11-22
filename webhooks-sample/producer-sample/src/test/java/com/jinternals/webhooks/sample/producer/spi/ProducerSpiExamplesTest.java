package com.jinternals.webhooks.sample.producer.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end verification of the producer SPI examples documented in {@code docs/reference/beans-and-spis.md}:
 * {@link DeadLetterDeliveryListener}, {@link BearerTokenWebhookInterceptor}, and
 * {@code @Bean("destinationSecretProvider")}.
 */
@SpringBootTest
@ActiveProfiles("spi-examples")
class ProducerSpiExamplesTest {

    private static HttpServer server;
    private static final AtomicReference<Integer> nextStatus = new AtomicReference<>(204);
    private static final AtomicReference<Map<String, List<String>>> receivedHeaders = new AtomicReference<>();

    @BeforeAll
    static void startStubReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks/crm", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            int status = nextStatus.get();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopStubReceiver() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureDestination(DynamicPropertyRegistry registry) {
        registry.add(
                "webhooks.producer.destinations.demo.url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/crm");
        registry.add("webhooks.producer.retry.max-attempts", () -> 2);
        registry.add("webhooks.producer.retry.initial-backoff", () -> "1ms");
        registry.add("webhooks.producer.retry.max-backoff", () -> "2ms");
    }

    @Autowired
    private CrmWebhooks crmWebhooks;

    @Autowired
    private DeadLetterDeliveryListener deadLetterListener;

    @Autowired
    @Qualifier("destinationSecretProvider")
    private SecretProvider<WebhookProducerProperties.Destination> destinationSecretProvider;

    private static ContactCreated sampleEvent(String id) {
        ContactCreated event = new ContactCreated();
        event.setId(id);
        event.setFullName("Ada Lovelace");
        event.setEmail("ada@example.com");
        return event;
    }

    @Test
    void attachesBearerTokenAlongsideSignedWebhookHeadersAndUsesCustomDestinationSecretProvider() {
        assertThat(destinationSecretProvider)
                .isNotInstanceOf(com.jinternals.webhooks.producer.secret.ConfigDestinationSecretProvider.class);

        nextStatus.set(204);
        crmWebhooks.contactCreated(sampleEvent("c-auth-1"));

        Map<String, List<String>> headers = receivedHeaders.get();
        assertThat(headers.get("Authorization")).containsExactly("Bearer demo-access-token");
        assertThat(headers.get("Webhook-id")).hasSize(1);
        assertThat(headers.get("Webhook-signature")).hasSize(1);
    }

    @Test
    void capturesAbandonedDeliveryInDeadLetterListenerWhenDestinationRejectsWithFatal4xx() {
        deadLetterListener.clear();
        nextStatus.set(404);

        assertThatThrownBy(() -> crmWebhooks.contactCreated(sampleEvent("c-dlq-1")))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(deadLetterListener.deadLetters()).hasSize(1);
        DeliveryFailure failure = deadLetterListener.deadLetters().get(0);
        assertThat(failure.reason()).isEqualTo(DeliveryFailure.Reason.FATAL);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(failure.headers()).containsKey(WebhookHeaders.SIGNATURE);
        assertThat(failure.body()).contains("\"contact.created\"");
    }
}
