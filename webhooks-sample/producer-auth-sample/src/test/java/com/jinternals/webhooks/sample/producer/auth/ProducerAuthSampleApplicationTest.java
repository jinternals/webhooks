package com.jinternals.webhooks.sample.producer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "webhooks.producer.retry.max-attempts=2",
            "webhooks.producer.retry.initial-backoff=1ms",
            "webhooks.producer.retry.max-backoff=2ms"
        })
class ProducerAuthSampleApplicationTest {

    private static HttpServer server;
    private static final AtomicInteger status = new AtomicInteger(204);
    private static final List<Map<String, List<String>>> capturedHeaders = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks/crm", exchange -> {
            capturedHeaders.add(exchange.getRequestHeaders());
            int current = status.get();
            if (current == 503) {
                status.set(204); // succeed on retry
            }
            exchange.sendResponseHeaders(current, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopReceiver() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "webhooks.producer.destinations.crm.url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/crm");
    }

    @Autowired
    private ProducerAuthSampleApplication.CrmWebhooks crmWebhooks;

    @Test
    void attachesJwtBearerTokenAlongsideStandardWebhooksHeadersOnEveryAttempt() {
        capturedHeaders.clear();
        status.set(503);

        crmWebhooks.contactCreated(
                new ProducerAuthSampleApplication.ContactCreated("c-1", "Ada Lovelace", "ada@example.com"));

        assertThat(capturedHeaders).hasSize(2);
        for (Map<String, List<String>> attemptHeaders : capturedHeaders) {
            assertThat(attemptHeaders.get("Authorization"))
                    .singleElement()
                    .asString()
                    .startsWith("Bearer eyJ");
            assertThat(attemptHeaders.get("Webhook-id")).hasSize(1);
            assertThat(attemptHeaders.get("Webhook-timestamp")).hasSize(1);
            assertThat(attemptHeaders.get("Webhook-signature")).hasSize(1);
        }
    }
}
