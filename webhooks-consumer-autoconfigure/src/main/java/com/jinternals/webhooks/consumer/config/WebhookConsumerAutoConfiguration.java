package com.jinternals.webhooks.consumer.config;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Handler;
import com.jinternals.webhooks.consumer.dispatch.AsyncHandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.HandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import com.jinternals.webhooks.consumer.dispatch.NoOpHandlerFailureListener;
import com.jinternals.webhooks.consumer.dispatch.SyncHandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.idempotency.InMemoryIdempotencyGuard;
import com.jinternals.webhooks.consumer.idempotency.NoOpIdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataContributor;
import com.jinternals.webhooks.consumer.metadata.MetadataFactory;
import com.jinternals.webhooks.consumer.metrics.MicrometerWebhookMetrics;
import com.jinternals.webhooks.consumer.metrics.NoOpWebhookMetrics;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.registry.WebhookTypeScanner;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.verify.ClockSkewTimestampValidator;
import com.jinternals.webhooks.consumer.verify.ConfigSecretProvider;
import com.jinternals.webhooks.consumer.verify.DefaultWebhookVerifier;
import com.jinternals.webhooks.consumer.verify.NoOpTimestampValidator;
import com.jinternals.webhooks.consumer.verify.NoOpWebhookVerifier;
import com.jinternals.webhooks.consumer.verify.SecretValidator;
import com.jinternals.webhooks.consumer.verify.TimestampValidator;
import com.jinternals.webhooks.consumer.verify.WebhookVerifier;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.core.secret.SecretProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration for the Standard Webhooks consumer (see SPEC.md §14). An existing Spring Boot
 * application only needs the {@code webhooks-consumer} dependency and {@code webhooks.consumer.*}
 * properties; it does <strong>not</strong> component-scan this library.
 *
 * <p><strong>Drop-in by design.</strong> Every bean is declared with an explicit {@code @Bean} method
 * guarded by {@link ConditionalOnMissingBean}, so the library:
 * <ul>
 *   <li>never component-scans (no collision with the host app's own scanning, no eager side effects);</li>
 *   <li>lets the host override any piece ({@link WebhookVerifier}, {@link SecretProvider},
 *       {@link IdempotencyGuard}, {@link HandlerExecutor}, …) just by defining its own bean;</li>
 *   <li>contributes {@link MetadataContributor} beans the host publishes, via {@link ObjectProvider}.</li>
 * </ul>
 *
 * <p>Web-layer beans (controller, MVC customizations, error handler, endpoint registration) live in
 * {@link WebhookConsumerWebAutoConfiguration}, which is gated on a servlet web application.
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration(
        after = {JacksonAutoConfiguration.class, ValidationAutoConfiguration.class},
        afterName = {
            "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        })
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConsumerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebhookConsumerAutoConfiguration.class);

    /**
     * The {@code type → @WebhookType class} registry. <strong>Zero-config:</strong> the {@code @WebhookType} event
     * classes are discovered by scanning the host application's auto-configuration packages — i.e. the
     * package of its {@code @SpringBootApplication} class and any additional {@code @AutoConfigurationPackage}
     * sources, as reported by {@link AutoConfigurationPackages}. A library user puts their event classes
     * anywhere under their app's root package and does not configure a scan root.
     *
     * <p>Override by publishing your own {@code @Bean WebhookTypeRegistry} (e.g. built from an explicit package
     * via {@link WebhookTypeScanner#scan(String)}) when your events live outside the app's package hierarchy.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookTypeRegistry webhookTypeRegistry(ListableBeanFactory beanFactory) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            throw new IllegalStateException(
                    "No auto-configuration base packages found to scan for @WebhookType classes. Annotate your "
                            + "application with @SpringBootApplication (or @AutoConfigurationPackage), or "
                            + "publish your own @Bean WebhookTypeRegistry.");
        }
        List<String> basePackages = AutoConfigurationPackages.get(beanFactory);
        return WebhookTypeScanner.scan(basePackages);
    }

    /**
     * Replay-window (timestamp freshness) policy (SPEC §8b). <strong>On by default</strong>
     * ({@link ClockSkewTimestampValidator} with {@code webhooks.consumer.replay-tolerance}, default 5m, per
     * Standard Webhooks); when {@code webhooks.consumer.replay-window-enabled=false} it becomes a
     * {@link NoOpTimestampValidator}. Override with a {@code @Bean TimestampValidator} for a custom policy.
     */
    @Bean
    @ConditionalOnMissingBean
    public TimestampValidator webhookTimestampValidator(
            WebhookProperties properties, ObjectProvider<Clock> clockProvider) {
        WebhookProperties.Consumer consumer = properties.getConsumer();
        return consumer.isReplayWindowEnabled()
                ? new ClockSkewTimestampValidator(
                        consumer.getReplayTolerance(), clockProvider.getIfAvailable(Clock::systemUTC))
                : new NoOpTimestampValidator();
    }

    /**
     * Message-verification policy (SPEC §8, §8f, §8e). <strong>On by default</strong>: a
     * {@link DefaultWebhookVerifier} checks the Standard Webhooks {@code webhook-signature} header against the
     * endpoint's secret(s), selecting the scheme per secret by prefix — {@code whsec_} for symmetric
     * {@code v1} (HMAC) or {@code whpk_} for asymmetric {@code v2a} (Ed25519) — with the replay-window check
     * delegated to the injected {@link TimestampValidator} (SPEC §8b). When
     * {@code webhooks.consumer.signature-verification-enabled=false} it becomes a {@link NoOpWebhookVerifier}
     * that accepts every request, and a prominent WARN is logged at every startup. Override with a
     * {@code @Bean WebhookVerifier} for a different scheme.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookVerifier webhookVerifier(WebhookProperties properties, TimestampValidator timestampValidator) {
        if (!properties.getConsumer().isSignatureVerificationEnabled()) {
            log.warn("SECURITY: webhook signature verification is DISABLED "
                    + "(webhooks.consumer.signature-verification-enabled=false). Every inbound request is "
                    + "accepted WITHOUT checking its signature — the endpoint will accept forged payloads from "
                    + "anyone who can reach it. Only safe when the transport is authenticated (reverse proxy / "
                    + "mTLS / Spring Security).");
            return new NoOpWebhookVerifier();
        }
        return new DefaultWebhookVerifier(timestampValidator);
    }

    /** Per-type payload validation via Bean Validation on the {@code @WebhookType} class (SPEC §6a). */
    @Bean
    @ConditionalOnMissingBean
    public PayloadValidator webhookPayloadValidator(Validator validator) {
        return new PayloadValidator(validator);
    }

    /** Builds the immutable {@code WebhookMetadata}; contributes any host-published contributors (SPEC §9). */
    @Bean
    @ConditionalOnMissingBean
    public MetadataFactory webhookMetadataFactory(ObjectProvider<MetadataContributor> contributors) {
        return new MetadataFactory(contributors.orderedStream().toList());
    }

    /** Scans all beans for {@code @WebhookHandler} methods and routes verified events to them (SPEC §10). */
    @Bean
    @ConditionalOnMissingBean
    public WebhookDispatcher webhookDispatcher(ApplicationContext applicationContext) {
        return new WebhookDispatcher(applicationContext);
    }

    /** No-op by default; in-memory best-effort when {@code webhooks.consumer.in-memory-idempotency} is true. */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(WebhookProperties properties, ObjectProvider<Clock> clockProvider) {
        return properties.getConsumer().isInMemoryIdempotency()
                ? new InMemoryIdempotencyGuard(
                        properties.getConsumer().getInMemoryIdempotencyMaxSize(),
                        clockProvider.getIfAvailable(Clock::systemUTC))
                : new NoOpIdempotencyGuard();
    }

    /** Default secret source: Spring config (endpoint {@code secret} + {@code additional-secrets}). */
    @Bean
    @ConditionalOnMissingBean
    public SecretProvider<Endpoint> secretProvider() {
        return new ConfigSecretProvider();
    }

    /** Fails the context at startup if any endpoint secret is missing or malformed (SPEC §14a). */
    @Bean
    @ConditionalOnMissingBean
    public SecretValidator secretValidator(WebhookProperties properties, SecretProvider<Endpoint> secretProvider) {
        return new SecretValidator(properties, secretProvider);
    }

    /**
     * Failure hook for {@code async} handler execution (SPEC §15, §20). Notified when an async handler
     * throws or times out — the delivery was already acknowledged with {@code 204}, so this is the only
     * signal it did not complete. <strong>No-op by default</strong> (sync mode never uses it); publish a
     * {@code @Bean HandlerFailureListener} to persist/alert on async handler failures.
     */
    @Bean
    @ConditionalOnMissingBean
    public HandlerFailureListener webhookHandlerFailureListener() {
        return new NoOpHandlerFailureListener();
    }

    /**
     * Handler execution strategy (see SPEC.md §15, §18): inline {@code SYNC} by default, or a bounded
     * {@code ASYNC} pool with a per-handler timeout when {@code webhooks.consumer.handler.mode: async}.
     * In {@code async} mode a handler failure or timeout is routed to the {@link HandlerFailureListener}
     * (SPEC §20), since the {@code 204} has already been sent.
     */
    @Bean
    @ConditionalOnMissingBean
    public HandlerExecutor handlerExecutor(WebhookProperties properties, HandlerFailureListener failureListener) {
        Handler handler = properties.getConsumer().getHandler();
        if (handler.getMode() == Handler.Mode.ASYNC) {
            return new AsyncHandlerExecutor(
                    handler.getCorePoolSize(),
                    handler.getMaxPoolSize(),
                    handler.getQueueCapacity(),
                    handler.getTimeout(),
                    failureListener);
        }
        return new SyncHandlerExecutor();
    }

    /**
     * Inject-safe fallback {@link ObservationRegistry} (SPEC §13). Under the standard Spring stack the
     * host app's actuator provides a real registry wired to its {@code MeterRegistry} (and a tracer bridge
     * if present), and that one is used. When nothing provides one this resolves to
     * {@link ObservationRegistry#NOOP}, so {@code @Observed} is always satisfiable and the hot path stays
     * branch-free. Disable individual observations the standard way:
     * {@code management.observations.enable.webhook.receive=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry webhookObservationRegistry() {
        return ObservationRegistry.NOOP;
    }

    /**
     * Enables {@link io.micrometer.observation.annotation.Observed @Observed} on proxied beans (the
     * dispatcher's {@code dispatch}) via the standard Micrometer {@link ObservedAspect}. Requires AOP on
     * the classpath; {@link ConditionalOnClass} keeps this a soft dependency.
     */
    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    @ConditionalOnMissingBean
    public ObservedAspect webhookObservedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Opt-in consumer metrics (SPEC §20.6/A4).
     *
     * <p>When {@code micrometer-core} is on the classpath (e.g. a host pulled in actuator), the nested
     * {@link MetricsConfiguration} supplies the {@link WebhookMetrics} bean, picking the Micrometer-backed
     * implementation if a {@link MeterRegistry} bean exists and the no-op otherwise. It resolves the
     * registry through an {@link ObjectProvider} <em>at bean-creation time</em> rather than via
     * {@link ConditionalOnBean}, so it is immune to autoconfiguration ordering: the registry is looked up
     * when the bean is built, after every {@code MeterRegistry} definition has been registered.
     *
     * <p>When {@code micrometer-core} is absent entirely, this whole nested configuration is skipped
     * ({@link ConditionalOnClass}) and {@link #noOpWebhookMetrics()} provides the inert default — so a host
     * without Micrometer neither needs nor loads the meter-backed code.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        /**
         * The active {@link WebhookMetrics}: {@link MicrometerWebhookMetrics} when a {@link MeterRegistry}
         * is present, else {@link NoOpWebhookMetrics}. Override with a {@code @Bean WebhookMetrics} to target
         * any backend (this is {@link ConditionalOnMissingBean}).
         */
        @Bean
        @ConditionalOnMissingBean
        WebhookMetrics webhookMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            return registry == null ? new NoOpWebhookMetrics() : new MicrometerWebhookMetrics(registry);
        }
    }

    /**
     * Fallback {@link WebhookMetrics} for hosts <em>without</em> {@code micrometer-core} on the classpath:
     * records nothing, so the receive path is zero-cost. When Micrometer is present, {@link MetricsConfiguration}
     * supplies the bean first and this {@link ConditionalOnMissingBean} default never fires.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookMetrics noOpWebhookMetrics() {
        return new NoOpWebhookMetrics();
    }
}
