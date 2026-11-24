package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.dispatch.HandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataFactory;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.verify.WebhookVerifier;
import com.jinternals.webhooks.consumer.web.WebConfiguration;
import com.jinternals.webhooks.consumer.web.WebhookEndpointRegistry;
import com.jinternals.webhooks.consumer.web.WebhookReceiveController;
import com.jinternals.webhooks.consumer.web.WebhookReceivePipeline;
import com.jinternals.webhooks.consumer.web.error.EndpointNotConfiguredException;
import com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler;
import com.jinternals.webhooks.consumer.web.error.PayloadTooLargeException;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.core.secret.SecretProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test for {@link WebhookConsumerWebAutoConfiguration}: each web-layer {@code @Bean} method must
 * produce a bean that actually exhibits its contracted behaviour wired to the collaborators/properties it
 * was built with (SPEC.md §7, §10, §14) — asserted behaviourally, not by concrete type. The deep receive
 * flow is covered by {@code WebhookReceiveControllerTest}; the pipeline is no longer a set of individual
 * beans — the orchestrator composes its stages internally.
 */
class WebhookConsumerWebAutoConfigurationTest {

    private final WebhookConsumerWebAutoConfiguration autoConfig = new WebhookConsumerWebAutoConfiguration();

    private static org.springframework.beans.factory.ObjectProvider<SecretProvider<Endpoint>> emptySecretProvider() {
        return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.core.ResolvableType.forClassWithGenerics(
                        SecretProvider.class, Endpoint.class));
    }

    private WebhookReceivePipeline pipeline() {
        var clockProvider = new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(java.time.Clock.class);
        return autoConfig.webhookReceivePipeline(
                mock(WebhookVerifier.class),
                emptySecretProvider(),
                new WebhookTypeRegistry(Map.of()),
                mock(PayloadValidator.class),
                new JsonMapper(),
                new WebhookProperties(),
                mock(IdempotencyGuard.class),
                mock(MetadataFactory.class),
                mock(HandlerExecutor.class),
                mock(WebhookDispatcher.class),
                mock(WebhookMetrics.class),
                clockProvider);
    }

    private WebhookReceiveController controller() {
        return autoConfig.webhookReceiveController(pipeline());
    }

    @com.jinternals.webhooks.core.annotations.WebhookType("contact.created")
    static class SampleEvent {
        public String id;
    }

    @Test
    void receivePipelineIsProducedAndWired() throws Exception {
        // Drive a real accepted delivery through the auto-configured pipeline so BOTH ConfigSecretProvider::new
        // and Clock::systemUTC fallbacks are evaluated (killing null-supplier mutants).
        WebhookMetrics metrics = mock(WebhookMetrics.class);
        WebhookVerifier verifier = mock(WebhookVerifier.class);
        IdempotencyGuard idempotency = new com.jinternals.webhooks.consumer.idempotency.NoOpIdempotencyGuard();
        WebhookTypeRegistry typeRegistry = new WebhookTypeRegistry(Map.of("contact.created", SampleEvent.class));
        MetadataFactory metadataFactory = new MetadataFactory(List.of());
        HandlerExecutor sync = new com.jinternals.webhooks.consumer.dispatch.SyncHandlerExecutor();
        WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
        var clockProvider = new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(java.time.Clock.class);

        WebhookReceivePipeline p = autoConfig.webhookReceivePipeline(
                verifier,
                emptySecretProvider(),
                typeRegistry,
                mock(PayloadValidator.class),
                new JsonMapper(),
                new WebhookProperties(),
                idempotency,
                metadataFactory,
                sync,
                dispatcher,
                metrics,
                clockProvider);

        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        Endpoint ep = endpoint("/webhooks/crm");
        ep.setSecret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        p.process(
                req,
                ep,
                "{\"type\":\"contact.created\",\"timestamp\":\"2023-11-14T22:13:20Z\",\"data\":{\"id\":\"1\"}}",
                Map.of("webhook-id", List.of("msg_1"), "webhook-timestamp", List.of("1700000000")));

        verify(verifier)
                .verify(
                        org.mockito.ArgumentMatchers.eq(List.of("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")),
                        any(),
                        any(),
                        any());
        verify(metrics).received("contact.created");
        verify(metrics).deliveryLag(any());
        verify(metrics).eventLag(any());

        // Even if the host JsonMapper disables FAIL_ON_TRAILING_TOKENS, the pipeline rejects trailing tokens!
        JsonMapper lenientMapper = JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        WebhookReceivePipeline strictPipeline = autoConfig.webhookReceivePipeline(
                verifier,
                emptySecretProvider(),
                typeRegistry,
                mock(PayloadValidator.class),
                lenientMapper,
                new WebhookProperties(),
                idempotency,
                metadataFactory,
                sync,
                dispatcher,
                metrics,
                clockProvider);
        assertThatThrownBy(() -> strictPipeline.process(
                        req,
                        ep,
                        "{\"type\":\"contact.created\",\"data\":{\"id\":\"1\"}} trailing",
                        Map.of("webhook-id", List.of("msg_2"), "webhook-timestamp", List.of("1700000000"))))
                .isInstanceOf(com.jinternals.webhooks.consumer.web.error.InvalidPayloadException.class);
    }

    @Test
    void receiveControllerRejectsARequestWithNoMatchedEndpoint() {
        // The controller must be wired to extract the matched endpoint from the request: a request with no
        // endpoint attribute makes handle() fail with EndpointNotConfiguredException before any pipeline
        // work. Asserting that behaviour proves the wiring far better than a bare class check.
        WebhookReceiveController controller = controller();

        HttpServletRequest request = mock(HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/unmapped");

        assertThatThrownBy(() -> controller.handle(request, "{}")).isInstanceOf(EndpointNotConfiguredException.class);
    }

    @Test
    void endpointRegistryRegistersAPostMappingPerConfiguredEndpoint() {
        // The registry must be wired to the properties it was built with: registering endpoints must add one
        // POST mapping per configured path to the handler mapping. Asserting the registrations (not the
        // class) proves the bean reads the injected properties and drives the injected handler mapping.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getEndpoints().add(endpoint("/webhooks/crm"));
        properties.getConsumer().getEndpoints().add(endpoint("/webhooks/billing"));
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);

        WebhookEndpointRegistry registry = autoConfig.webhookEndpointRegistry(handlerMapping, controller(), properties);
        registry.registerEndpoints();

        verify(handlerMapping, org.mockito.Mockito.times(2)).registerMapping(any(), any(), any());
    }

    @Test
    void webConfigurationContributesTheRawBodyResolverAndScopesItsInterceptor() throws Exception {
        // The MVC configurer must actually contribute the webhook machinery: it adds a raw-body argument
        // resolver that supports the controller's String rawBody parameter, and registers its interceptor
        // only for the configured webhook paths. Asserting the contributed resolver's behaviour (and that
        // interceptor registration runs for the configured path) proves the config was wired, not just typed.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getEndpoints().add(endpoint("/webhooks/crm"));

        WebConfiguration config = autoConfig.webhookWebConfiguration(properties);

        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        config.addArgumentResolvers(resolvers);
        assertThat(resolvers).hasSize(1);
        MethodParameter rawBodyParam = new MethodParameter(
                WebhookReceiveController.class.getMethod("handle", HttpServletRequest.class, String.class), 1);
        assertThat(resolvers.get(0).supportsParameter(rawBodyParam)).isTrue();

        assertThatCode(() -> config.addInterceptors(new InterceptorRegistry())).doesNotThrowAnyException();
    }

    @Test
    void exceptionHandlerMapsAKnownFailureToItsProblemDetail() {
        // The advice bean must actually map webhook failures to RFC-7807 problem details with the right
        // status. Driving one handler method (payload-too-large -> 413) proves it is a working handler;
        // the full status/URN matrix is covered by GlobalExceptionHandlerTest.
        GlobalExceptionHandler handler = autoConfig.webhookExceptionHandler();

        ProblemDetail problem = handler.onPayloadTooLarge(new PayloadTooLargeException(256));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(problem.getProperties()).containsEntry("limitBytes", 256L);
    }

    private static Endpoint endpoint(String path) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath(path);
        return endpoint;
    }
}
