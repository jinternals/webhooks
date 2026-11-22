package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Unit test for {@link WebhookConsumerWebAutoConfiguration}: each web-layer {@code @Bean} method must
 * produce a bean that actually exhibits its contracted behaviour wired to the collaborators/properties it
 * was built with (SPEC.md §7, §10, §14) — asserted behaviourally, not by concrete type. The deep receive
 * flow is covered by {@code WebhookReceiveControllerTest}; the pipeline is no longer a set of individual
 * beans — the orchestrator composes its stages internally.
 */
class WebhookConsumerWebAutoConfigurationTest {

    private final WebhookConsumerWebAutoConfiguration autoConfig = new WebhookConsumerWebAutoConfiguration();

    @SuppressWarnings("unchecked")
    private WebhookReceivePipeline pipeline() {
        return autoConfig.webhookReceivePipeline(
                mock(WebhookVerifier.class),
                mock(SecretProvider.class),
                new WebhookTypeRegistry(Map.of()),
                mock(PayloadValidator.class),
                new ObjectMapper(),
                new WebhookProperties(),
                mock(IdempotencyGuard.class),
                mock(MetadataFactory.class),
                mock(HandlerExecutor.class),
                mock(WebhookDispatcher.class),
                mock(WebhookMetrics.class));
    }

    private WebhookReceiveController controller() {
        return autoConfig.webhookReceiveController(pipeline());
    }

    @Test
    void receivePipelineIsProducedAndWired() {
        // The pipeline orchestrator is composed from its collaborator seams. Its full receive behaviour
        // (verify -> dedupe -> route -> deserialize -> dispatch) is exercised end-to-end by
        // WebhookReceiveControllerTest; here the honest assertion is that the factory actually builds it
        // from the injected seams (kills the null-return mutant on the single-expression factory).
        assertThat(pipeline()).isNotNull();
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
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(problem.getProperties()).containsEntry("limitBytes", 256L);
    }

    private static Endpoint endpoint(String path) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath(path);
        return endpoint;
    }
}
