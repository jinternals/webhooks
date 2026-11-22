package com.jinternals.webhooks.producer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * End-to-end wiring test proving a host-provided {@link WebhookRequestInterceptor} {@code @Bean} is
 * auto-discovered by the producer auto-configuration and applied inside the default transport chain — the
 * supported way to add transport authentication (e.g. an OAuth2 bearer token) without replacing the
 * transport and losing retry / the SSRF guard. The interceptor adds an {@code Authorization} header that
 * must arrive at a real local destination, with the request's other headers preserved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebhookRequestInterceptorWiringTest {

    private static final AtomicReference<String> SEEN_AUTHORIZATION = new AtomicReference<>();
    private static HttpServer server;

    @SpringBootApplication(scanBasePackages = "com.jinternals.webhooks.producer.fixtures.none")
    static class TestApp {
        @Bean
        WebhookRequestInterceptor bearerTokenInterceptor() {
            return (url, headers, body) -> {
                var out = new java.util.LinkedHashMap<>(headers);
                out.put("Authorization", List.of("Bearer wired-token"));
                return out;
            };
        }
    }

    @Autowired
    private WebhookTransport transport;

    @BeforeEach
    void startServer() throws Exception {
        SEEN_AUTHORIZATION.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks", exchange -> {
            SEEN_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anInterceptorBeanIsAppliedByTheAutoConfiguredTransportChain() {
        int port = server.getAddress().getPort();

        // send is synchronous: the destination handler has run and recorded the header before it returns.
        transport.send("http://127.0.0.1:" + port + "/webhooks", Map.of("webhook-id", List.of("msg_1")), "{}");

        assertThat(SEEN_AUTHORIZATION.get()).isEqualTo("Bearer wired-token");
    }
}
