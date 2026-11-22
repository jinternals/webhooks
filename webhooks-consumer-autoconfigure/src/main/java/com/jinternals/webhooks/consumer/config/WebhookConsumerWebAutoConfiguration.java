package com.jinternals.webhooks.consumer.config;

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
import com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler;
import com.jinternals.webhooks.consumer.web.receive.EventResolver;
import com.jinternals.webhooks.consumer.web.receive.ReceiveObservabilityRecorder;
import com.jinternals.webhooks.consumer.web.receive.TimedHandlerDispatcher;
import com.jinternals.webhooks.consumer.web.receive.WebhookBodyVerifier;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.core.secret.SecretProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Web-layer auto-configuration for the Standard Webhooks consumer (see SPEC.md §14), activated only in a
 * servlet web application and after Spring MVC has been configured. Kept separate from
 * {@link WebhookConsumerAutoConfiguration} so the core beans load even in a non-web context (tests,
 * batch) without dragging in MVC types.
 *
 * <p>Every bean is an explicit {@code @Bean} guarded by {@link ConditionalOnMissingBean}, so this never
 * component-scans and the host app can override any piece. The MVC customizations it contributes are
 * deliberately narrow:
 * <ul>
 *   <li>{@link WebConfiguration} scopes its interceptor to the configured webhook paths only;</li>
 *   <li>{@link GlobalExceptionHandler} is bound to {@link WebhookReceiveController} only, so it never
 *       intercepts the host app's own controllers.</li>
 * </ul>
 */
@AutoConfiguration(after = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebhookConsumerWebAutoConfiguration {

    /**
     * The receive pipeline orchestrator (verify → dedupe → resolve → metadata → observability → dispatch;
     * SPEC §10), separated from the thin {@link WebhookReceiveController} HTTP adapter.
     *
     * <p>The single-responsibility stages ({@link WebhookBodyVerifier}, {@link EventResolver},
     * {@link ReceiveObservabilityRecorder}, {@link TimedHandlerDispatcher}) are internal collaborators the
     * pipeline composes itself — deliberately <em>not</em> published as individual beans, so they stay an
     * implementation detail rather than supported extension points. The stable, overridable seams are the
     * collaborators injected here ({@code WebhookVerifier}, {@code SecretProvider}, {@code IdempotencyGuard},
     * {@code HandlerExecutor}, {@code WebhookDispatcher}, {@code MetadataFactory}, {@code WebhookMetrics}),
     * each its own {@code @ConditionalOnMissingBean} bean elsewhere.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookReceivePipeline webhookReceivePipeline(
            WebhookVerifier verifier,
            SecretProvider<Endpoint> secretProvider,
            WebhookTypeRegistry webhookTypeRegistry,
            PayloadValidator payloadValidator,
            ObjectMapper objectMapper,
            WebhookProperties properties,
            IdempotencyGuard idempotencyGuard,
            MetadataFactory metadataFactory,
            HandlerExecutor handlerExecutor,
            WebhookDispatcher dispatcher,
            WebhookMetrics metrics) {
        WebhookBodyVerifier bodyVerifier = new WebhookBodyVerifier(verifier, secretProvider, metrics);
        EventResolver eventResolver =
                new EventResolver(webhookTypeRegistry, payloadValidator, objectMapper, properties);
        ReceiveObservabilityRecorder observabilityRecorder = new ReceiveObservabilityRecorder(metrics);
        TimedHandlerDispatcher eventDispatcher = new TimedHandlerDispatcher(handlerExecutor, dispatcher, metrics);
        return new WebhookReceivePipeline(
                bodyVerifier,
                idempotencyGuard,
                objectMapper,
                eventResolver,
                metadataFactory,
                observabilityRecorder,
                eventDispatcher);
    }

    /** The single generic controller behind every configured listen path (SPEC §3, §10). */
    @Bean
    @ConditionalOnMissingBean
    public WebhookReceiveController webhookReceiveController(WebhookReceivePipeline pipeline) {
        return new WebhookReceiveController(pipeline);
    }

    /** Registers one POST mapping per configured path, all pointing at the generic controller (SPEC §7). */
    @Bean
    @ConditionalOnMissingBean
    public WebhookEndpointRegistry webhookEndpointRegistry(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            WebhookReceiveController controller,
            WebhookProperties properties) {
        return new WebhookEndpointRegistry(handlerMapping, controller, properties);
    }

    /** Raw-body argument resolver + webhook-path-scoped endpoint-resolving interceptor (SPEC §7). */
    @Bean
    @ConditionalOnMissingBean
    public WebConfiguration webhookWebConfiguration(WebhookProperties properties) {
        return new WebConfiguration(properties);
    }

    /** RFC-7807 error responses, scoped to the webhook controller only (SPEC §12, §14). */
    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler webhookExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
