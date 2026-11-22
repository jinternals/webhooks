package com.jinternals.webhooks.producer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.client.WebhookPublisher;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.AllowListDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.sign.DefaultWebhookSigner;
import com.jinternals.webhooks.producer.sign.NoOpWebhookSigner;
import com.jinternals.webhooks.producer.sign.WebhookSigner;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.NoOpWebhookDeliveryListener;
import com.jinternals.webhooks.producer.transport.RestClientWebhookTransport;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unit test for {@link WebhookProducerAutoConfiguration}: each {@code @Bean} method must produce a bean that
 * actually exhibits its contracted behaviour (asserted behaviourally, not by concrete type), and
 * {@code webhookDestinationUrlValidator} must switch between the allow-all and allow-list policies based on
 * {@code webhooks.producer.security.allowed-hosts} (SPEC.md §19, §19d).
 */
class WebhookProducerAutoConfigurationTest {

    private final WebhookProducerAutoConfiguration autoConfig = new WebhookProducerAutoConfiguration();

    @Test
    void restClientDoesNotFollowRedirectsSoA3xxSurfacesAsAFailure() throws Exception {
        // The default RestClient must be built with redirect-following DISABLED: Standard Webhooks treats a
        // 3xx as a delivery failure, and following it would also bypass the destination allow-list. Prove it
        // end-to-end against a real local server that 302-redirects to a second endpoint: the client must NOT
        // chase the redirect (the second endpoint is never hit), and the 302 must surface to the transport as
        // a WebhookDeliveryException. This exercises the real factory wiring (no mock), so a mutant that
        // dropped the Redirect.NEVER setting would let the client follow the redirect and this test would fail.
        AtomicInteger redirectTargetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks", exchange -> {
            exchange.getResponseHeaders().add("Location", "/followed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/followed", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            RestClient restClient = autoConfig.webhookProducerRestClient(RestClient.builder(), properties());
            RestClientWebhookTransport transport =
                    new RestClientWebhookTransport(restClient, new AllowAllDestinationUrlValidator());

            assertThatThrownBy(() -> transport.send("http://127.0.0.1:" + port + "/webhooks", Map.of(), "{}"))
                    .isInstanceOf(WebhookDeliveryException.class)
                    .hasMessageContaining("failed with status 302");
            assertThat(redirectTargetHits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restClientAppliesTheConfiguredReadTimeout() throws Exception {
        // The read timeout must be applied so a stalled destination cannot pin the calling thread. Prove it
        // against a real server that accepts the connection but never responds: with a sub-second read
        // timeout the send must fail promptly (a transport error, no HTTP status) rather than block. A mutant
        // dropping setReadTimeout would hang past this bound; the hung-task guard / assertion window catches it.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks", exchange -> {
            // Never send a response: hold the exchange open until the client times out and closes.
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            WebhookProducerProperties properties = properties();
            properties.getHttp().setReadTimeout(Duration.ofMillis(250));
            RestClient restClient = autoConfig.webhookProducerRestClient(RestClient.builder(), properties);
            RestClientWebhookTransport transport =
                    new RestClientWebhookTransport(restClient, new AllowAllDestinationUrlValidator());

            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.send("http://127.0.0.1:" + port + "/webhooks", Map.of(), "{}"))
                    .isInstanceOf(WebhookDeliveryException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(4_000);
        } finally {
            server.stop(0);
        }
    }

    private static WebhookProducerProperties properties() {
        return new WebhookProducerProperties();
    }

    @Test
    void urlValidatorPermitsAnyDestinationWhenNoAllowedHostsConfigured() {
        // No allow-list (default): the selected validator must accept any destination URL. Asserting the
        // permissive behaviour (not the class) is what distinguishes the allow-all policy from the allow-list.
        WebhookProducerProperties properties = new WebhookProducerProperties();

        DestinationUrlValidator validator = autoConfig.webhookDestinationUrlValidator(properties);

        assertThatCode(() -> validator.validate("https://anything.example.com/webhooks"))
                .doesNotThrowAnyException();
    }

    @Test
    void urlValidatorRejectsHostsOutsideTheAllowListWhenConfigured() {
        // An allow-list is configured: the selected validator must permit a listed host and reject an
        // unlisted one. Asserting the accept+reject behaviour proves the allow-list policy was chosen.
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getSecurity().getAllowedHosts().add("crm.example.com");

        DestinationUrlValidator validator = autoConfig.webhookDestinationUrlValidator(properties);

        assertThatCode(() -> validator.validate("https://crm.example.com/webhooks"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate("https://evil.example.com/webhooks"))
                .isInstanceOf(WebhookDeliveryException.class);
    }

    @Test
    void deliveryListenerDefaultsToASilentNoOp() {
        // The default listener must silently absorb an abandoned-delivery notification (no throw) — the
        // no-op behaviour the retry transport relies on when there is no host dead-letter handler.
        WebhookDeliveryListener listener = autoConfig.webhookDeliveryListener();

        DeliveryFailure failure = new DeliveryFailure(
                "https://crm.example.com/webhooks",
                Map.of(),
                "{}",
                3,
                DeliveryFailure.Reason.EXHAUSTED,
                new WebhookDeliveryException("down", 503));
        assertThatCode(() -> listener.onDeliveryFailed(failure)).doesNotThrowAnyException();
    }

    private static org.springframework.beans.factory.ObjectProvider<java.time.Clock> emptyClockProvider() {
        return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(java.time.Clock.class);
    }

    @Test
    void transportEnforcesTheUrlValidatorBeforeSending() {
        // The produced transport must be the retrying wrapper wired with the URL validator: delivering to a
        // disallowed host fails fast with a WebhookDeliveryException and never touches the RestClient — the
        // observable behaviour of the wrapper, asserted without naming RetryingWebhookTransport.
        RestClient restClient = mock(RestClient.class, invocation -> {
            throw new AssertionError("RestClient must not be used when the URL is rejected");
        });
        DestinationUrlValidator denyAll = new AllowListDestinationUrlValidator(List.of("only.example.com"));

        WebhookTransport transport = autoConfig.webhookTransport(
                restClient,
                denyAll,
                new NoOpWebhookDeliveryListener(),
                new WebhookProducerProperties(),
                List.of(),
                emptyClockProvider());

        assertThatThrownBy(() -> transport.send("https://evil.example.com/webhooks", Map.of(), "{}"))
                .isInstanceOf(WebhookDeliveryException.class);
    }

    @Test
    void transportAppliesRequestInterceptorsInsideTheChainBeforeTheHttpSend() throws Exception {
        // The default transport must thread configured WebhookRequestInterceptors through to the actual HTTP
        // send, INSIDE the retry/SSRF layers. Prove it end-to-end against a real local server: an interceptor
        // that adds an Authorization header must result in that header arriving at the destination, while the
        // signed webhook-* header it was given is preserved. A mutant that skipped wiring the interceptors
        // (or dropped the intercepting layer) would deliver without the Authorization header and fail this.
        java.util.concurrent.atomic.AtomicReference<String> seenAuth =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenSigned =
                new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenSigned.set(exchange.getRequestHeaders().getFirst("webhook-id"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            RestClient restClient = autoConfig.webhookProducerRestClient(RestClient.builder(), properties());
            WebhookRequestInterceptor addBearer = (url, headers, body) -> {
                var out = new java.util.LinkedHashMap<>(headers);
                out.put("Authorization", List.of("Bearer token-123"));
                return out;
            };

            WebhookTransport transport = autoConfig.webhookTransport(
                    restClient,
                    new AllowAllDestinationUrlValidator(),
                    new NoOpWebhookDeliveryListener(),
                    properties(),
                    List.of(addBearer),
                    emptyClockProvider());

            transport.send("http://127.0.0.1:" + port + "/webhooks", Map.of("webhook-id", List.of("msg_1")), "{}");

            assertThat(seenAuth.get()).isEqualTo("Bearer token-123");
            assertThat(seenSigned.get()).isEqualTo("msg_1"); // the signed header survives interception
        } finally {
            server.stop(0);
        }
    }

    @Test
    void signerProducesAVerifiableSignature() {
        // The default signer bean (signing enabled) must actually sign: a produced signature for a known
        // secret/payload is non-blank and versioned (v1,...). Proves it is a working signer, not merely the
        // right class, and that it reports itself enabled.
        WebhookSigner signer = autoConfig.webhookSigner(new WebhookProducerProperties());

        assertThat(signer.isEnabled()).isTrue();
        String signature = signer.sign("whsec_c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0", null, "msg_1", 1700000000L, "{}");
        assertThat(signature).startsWith("v1,");
    }

    @Test
    void signerBeanIsTheNoOpWhenSigningIsDisabled() {
        // webhooks.producer.signing-enabled=false must select the disabled signer, so deliveries go unsigned.
        // A mutant ignoring the flag (always returning Hmac) would report enabled here and fail this.
        WebhookProducerProperties disabled = new WebhookProducerProperties();
        disabled.setSigningEnabled(false);

        WebhookSigner signer = autoConfig.webhookSigner(disabled);

        assertThat(signer).isInstanceOf(NoOpWebhookSigner.class);
        assertThat(signer.isEnabled()).isFalse();
    }

    @Test
    void destinationSecretProviderResolvesFromDestinationConfig() {
        // The default provider must resolve signing secrets from the destination's own config (primary +
        // additional), which is the config-backed behaviour — asserted by what it returns, not its class.
        SecretProvider<Destination> provider = autoConfig.destinationSecretProvider();

        Destination destination = new Destination();
        destination.setSecret("whsec_primary");
        destination.setAdditionalSecrets(List.of("whsec_old"));
        assertThat(provider.secretsFor(destination)).containsExactly("whsec_primary", "whsec_old");
    }

    @Test
    void publisherRejectsAnUnknownDestination() {
        // The produced publisher must be wired to the properties: publishing to a destination that is not
        // configured fails with a clear IllegalArgumentException, proving the wiring beyond a non-null check.
        @SuppressWarnings("unchecked")
        SecretProvider<Destination> secretProvider = mock(SecretProvider.class);

        WebhookPublisher publisher = autoConfig.webhookPublisher(
                new ObjectMapper(),
                new DefaultWebhookSigner(),
                mock(WebhookTransport.class),
                secretProvider,
                new WebhookProducerProperties(),
                emptyClockProvider());

        assertThatThrownBy(() -> publisher.publish("not-configured", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown webhook destination");
    }

    @com.jinternals.webhooks.core.annotations.WebhookType("contact.created")
    static class SampleEvent {
        public String id = "1";
    }

    @Test
    void publisherAndTransportDefaultClockFallbacksWorkEndToEnd() {
        // Exercises BOTH Clock::systemUTC fallbacks:
        // 1) publisher stamps envelope + headers with the fallback clock;
        // 2) transport parses an HTTP-date Retry-After header against the fallback clock.
        WebhookProducerProperties props = properties();
        Destination crm = new Destination();
        crm.setUrl("https://crm.example.com/webhooks");
        crm.setSecret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        props.getDestinations().put("crm", crm);

        WebhookTransport recordingTransport = mock(WebhookTransport.class);
        WebhookPublisher publisher = autoConfig.webhookPublisher(
                new ObjectMapper(),
                new DefaultWebhookSigner(),
                recordingTransport,
                autoConfig.destinationSecretProvider(),
                props,
                emptyClockProvider());
        publisher.publish("crm", new SampleEvent());
        org.mockito.Mockito.verify(recordingTransport)
                .send(
                        org.mockito.ArgumentMatchers.eq("https://crm.example.com/webhooks"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());

        // Exercise transport's Clock::systemUTC fallback via an HTTP-date Retry-After response.
        WebhookProducerProperties oneAttempt = properties();
        oneAttempt.getRetry().setMaxAttempts(1);
        RestClient dateFailingClient = RestClient.builder()
                .requestFactory(
                        (uri, method) -> new org.springframework.mock.http.client.MockClientHttpRequest(method, uri) {
                            @Override
                            protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                                org.springframework.mock.http.client.MockClientHttpResponse resp =
                                        new org.springframework.mock.http.client.MockClientHttpResponse(
                                                new byte[0], org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                                resp.getHeaders().set("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT");
                                return resp;
                            }
                        })
                .build();
        WebhookTransport transport = autoConfig.webhookTransport(
                dateFailingClient,
                new AllowAllDestinationUrlValidator(),
                new NoOpWebhookDeliveryListener(),
                oneAttempt,
                List.of(),
                emptyClockProvider());
        assertThatThrownBy(() -> transport.send("https://crm.example.com/webhooks", Map.of(), "{}"))
                .isInstanceOf(WebhookDeliveryException.class);
    }

    @Test
    void destinationValidatorRejectsAMisconfiguredDestinationAtStartup() {
        // The produced validator must actually enforce the startup contract: a destination with no URL fails
        // afterPropertiesSet. Asserting the behaviour (not the class) proves the bean is wired to the
        // properties + URL guard + secret provider.
        WebhookProducerProperties properties = properties();
        Destination destination = new Destination();
        destination.setSecret("whsec_abc"); // URL left null
        properties.getDestinations().put("crm", destination);

        DestinationValidator validator = autoConfig.webhookDestinationValidator(
                properties, new com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator(), d -> {
                    throw new AssertionError("secret provider should not be reached when the URL is missing");
                });

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(com.jinternals.webhooks.core.config.WebhookConfigurationException.class)
                .hasMessageContaining("No URL configured");
    }

    @Test
    void observationRegistryDefaultsToNoop() {
        assertThat(autoConfig.webhookObservationRegistry()).isSameAs(ObservationRegistry.NOOP);
    }

    @Test
    void observationRegistryBacksOffWhenActuatorObservationAutoConfigurationIsPresent() {
        // Regression guard: com.jinternals... sorts alphabetically BEFORE org.springframework.boot.actuate...
        // Without afterName = "...ObservationAutoConfiguration", our NOOP fallback registered first and caused
        // Actuator's ObservationAutoConfiguration to back off, silently disabling ALL observations in the host app.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        WebhookProducerAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.metrics.export.simple
                                .SimpleMetricsExportAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration
                                .class,
                        org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration.class))
                .run(ctx -> {
                    ObservationRegistry observationRegistry = ctx.getBean(ObservationRegistry.class);
                    assertThat(observationRegistry).isNotSameAs(ObservationRegistry.NOOP);
                    io.micrometer.observation.Observation.createNotStarted("test.obs", observationRegistry)
                            .observe(() -> {});
                    assertThat(ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                                    .find("test.obs")
                                    .timer())
                            .isNotNull();
                });
    }

    @Test
    void observedAspectIsProvidedForTheObservationRegistry() {
        // The factory constructs the standard Micrometer ObservedAspect (a third-party wrapper whose sole
        // job is to advise @Observed) from the given registry. Its internals are Micrometer's, not ours, so
        // the honest assertion is that the bean is actually produced (kills the null-return mutant); the real
        // advising is exercised by the @SpringBootTest wiring tests.
        ObservedAspect aspect = autoConfig.webhookObservedAspect(ObservationRegistry.create());

        assertThat(aspect).isNotNull();
    }
}
