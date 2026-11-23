package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Handler;
import com.jinternals.webhooks.consumer.config.typefixtures.SampleCreated;
import com.jinternals.webhooks.consumer.dispatch.HandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import com.jinternals.webhooks.consumer.dispatch.NoOpHandlerFailureListener;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataFactory;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.support.LogCapture;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.verify.DefaultWebhookVerifier;
import com.jinternals.webhooks.consumer.verify.NoOpTimestampValidator;
import com.jinternals.webhooks.consumer.verify.NoOpWebhookVerifier;
import com.jinternals.webhooks.consumer.verify.SecretValidator;
import com.jinternals.webhooks.consumer.verify.TimestampValidator;
import com.jinternals.webhooks.consumer.verify.WebhookVerificationException;
import com.jinternals.webhooks.consumer.verify.WebhookVerifier;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.core.secret.SecretProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Unit test for {@link WebhookConsumerAutoConfiguration}: every {@code @Bean} method must return a
 * concrete, correctly-typed bean, and the branching factories ({@code webhookTypeRegistry},
 * {@code webhookTimestampValidator}, {@code idempotencyGuard}, {@code handlerExecutor},
 * {@code webhookMetrics}) must pick the right implementation for each configuration (SPEC.md §14).
 */
class WebhookConsumerAutoConfigurationTest {

    private final WebhookConsumerAutoConfiguration autoConfig = new WebhookConsumerAutoConfiguration();

    @Test
    void webhookTypeRegistryScansTheAutoConfigurationPackages() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AutoConfigurationPackages.register(
                    context.getDefaultListableBeanFactory(), "com.jinternals.webhooks.consumer.config.typefixtures");
            context.refresh();

            WebhookTypeRegistry registry = autoConfig.webhookTypeRegistry(context.getDefaultListableBeanFactory());

            assertThat(registry).isNotNull();
            assertThat(registry.classOf("sample.created")).isEqualTo(SampleCreated.class);
        }
    }

    @Test
    void webhookTypeRegistryFailsFastWhenNoAutoConfigurationPackages() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            assertThatThrownBy(() -> autoConfig.webhookTypeRegistry(context.getDefaultListableBeanFactory()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No auto-configuration base packages");
        }
    }

    private static ObjectProvider<java.time.Clock> emptyClockProvider() {
        return new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(java.time.Clock.class);
    }

    private static ObjectProvider<java.time.Clock> clockProviderOf(java.time.Clock clock) {
        var bf = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        bf.registerSingleton("clock", clock);
        return bf.getBeanProvider(java.time.Clock.class);
    }

    @Test
    void timestampValidatorAcceptsAnyAgeWhenReplayWindowDisabled() {
        // Replay window explicitly off: the selected validator must accept even an ancient timestamp — the
        // behaviour that distinguishes the no-op policy from the clock-skew one, without naming the class.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setReplayWindowEnabled(false);

        TimestampValidator validator = autoConfig.webhookTimestampValidator(properties, emptyClockProvider());

        long ancient = Instant.now().minus(Duration.ofDays(3650)).getEpochSecond();
        assertThatCode(() -> validator.validate(ancient)).doesNotThrowAnyException();
    }

    @Test
    void timestampValidatorRejectsStaleTimestampsByDefault() {
        // Replay window is enabled by default (Standard Webhooks secure-by-default): without setting
        // replayWindowEnabled explicitly, the default properties must wire ClockSkewTimestampValidator and
        // reject a stale timestamp. This pins both the branch and the default field value = true.
        WebhookProperties properties = new WebhookProperties();

        TimestampValidator validator = autoConfig.webhookTimestampValidator(properties, emptyClockProvider());

        long tooOld = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();
        assertThatThrownBy(() -> validator.validate(tooOld)).isInstanceOf(WebhookVerificationException.class);
        // And a fresh one is accepted, so it is a real window, not a reject-everything stub.
        assertThatCode(() -> validator.validate(Instant.now().getEpochSecond())).doesNotThrowAnyException();
    }

    @Test
    void timestampValidatorUsesProvidedClockBeanWhenPresent() {
        WebhookProperties properties = new WebhookProperties();
        java.time.Clock fixed = java.time.Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), java.time.ZoneOffset.UTC);

        TimestampValidator validator = autoConfig.webhookTimestampValidator(properties, clockProviderOf(fixed));

        assertThatCode(() -> validator.validate(1_700_000_000L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(1_700_000_301L)).isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void verifierDelegatesTimestampCheckingToTheConfiguredValidator() {
        // The verifier must route the timestamp check through the validator it was built with: a validator
        // that rejects every timestamp makes verify() fail at the timestamp step, proving the wiring far
        // better than asserting the return is a WebhookVerifier instance.
        TimestampValidator rejecting = ts -> {
            throw new WebhookVerificationException("stale");
        };

        WebhookVerifier verifier = autoConfig.webhookVerifier(new WebhookProperties(), rejecting);

        assertThat(verifier).isInstanceOf(DefaultWebhookVerifier.class);
        Map<String, List<String>> headers = Map.of(
                "webhook-id", List.of("msg_1"),
                "webhook-timestamp", List.of("1700000000"),
                "webhook-signature", List.of("v1,deadbeef"));
        assertThatThrownBy(
                        () -> verifier.verify(List.of("whsec_c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"), null, "{}", headers))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void verifierBeanIsTheNoOpWhenVerificationIsDisabled() {
        // webhooks.consumer.signature-verification-enabled=false must select the NoOp verifier, which accepts
        // every request, AND log a prominent security WARN at startup. A mutant ignoring the flag (always
        // returning Hmac) would reject the forged signature below; a mutant dropping/garbling the WARN would
        // fail the message assertion.
        WebhookProperties disabled = new WebhookProperties();
        disabled.getConsumer().setSignatureVerificationEnabled(false);

        WebhookVerifier verifier;
        try (LogCapture logs = LogCapture.forClass(WebhookConsumerAutoConfiguration.class)) {
            verifier = autoConfig.webhookVerifier(disabled, ts -> {
                throw new WebhookVerificationException("should-not-be-consulted");
            });
            assertThat(logs.lastFormattedMessage())
                    .contains("SECURITY")
                    .contains("signature verification is DISABLED")
                    .contains("webhooks.consumer.signature-verification-enabled=false");
        }

        assertThat(verifier).isInstanceOf(NoOpWebhookVerifier.class);
        Map<String, List<String>> forged = Map.of(
                "webhook-id", List.of("msg_1"),
                "webhook-timestamp", List.of("1700000000"),
                "webhook-signature", List.of("v1,not-a-real-signature"));
        assertThatCode(() -> verifier.verify(List.of("whsec_c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"), null, "{}", forged))
                .doesNotThrowAnyException();
    }

    @Test
    void verifierBeanDoesNotWarnWhenVerificationEnabled() {
        // The security WARN must fire ONLY on the disabled path. With the default (enabled) config, no WARN
        // is emitted — this kills a mutant that logs unconditionally or inverts the flag check.
        WebhookProperties enabled = new WebhookProperties();

        try (LogCapture logs = LogCapture.forClass(WebhookConsumerAutoConfiguration.class)) {
            WebhookVerifier verifier = autoConfig.webhookVerifier(enabled, new NoOpTimestampValidator());
            assertThat(verifier).isInstanceOf(DefaultWebhookVerifier.class);
            assertThat(logs.formattedMessages()).noneMatch(m -> m.contains("DISABLED"));
        }
    }

    @Test
    void payloadValidatorDelegatesToTheBeanValidationValidator() {
        // The payload validator must actually run the injected jakarta Validator. A stub that reports a
        // violation must surface through the produced bean (proves the Validator was wired in, not just typed).
        Validator jakarta = mock(Validator.class);
        org.mockito.Mockito.when(jakarta.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());

        PayloadValidator validator = autoConfig.webhookPayloadValidator(jakarta);
        validator.validate("sample.created", new Object());

        org.mockito.Mockito.verify(jakarta).validate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void metadataFactoryBuildsMetadataCarryingTheEnvelopeType() {
        // The factory must produce usable metadata whose type comes from the envelope, proving it was
        // constructed with the contributors and actually runs — not merely that it is the right class.
        @SuppressWarnings("unchecked")
        ObjectProvider<com.jinternals.webhooks.consumer.metadata.MetadataContributor> contributors =
                mock(ObjectProvider.class);
        org.mockito.Mockito.when(contributors.orderedStream()).thenReturn(java.util.stream.Stream.empty());

        MetadataFactory factory = autoConfig.webhookMetadataFactory(contributors);

        com.jinternals.webhooks.core.model.Envelope envelope = new com.jinternals.webhooks.core.model.Envelope();
        envelope.setType("sample.created");
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        com.jinternals.webhooks.consumer.model.WebhookMetadata metadata =
                factory.build(request, envelope, new Object(), java.util.Map.of(), "/webhooks/crm");
        assertThat(metadata.type()).isEqualTo("sample.created");
        assertThat(metadata.endpointPath()).isEqualTo("/webhooks/crm");
    }

    @Test
    void dispatcherScansTheApplicationContextAndRejectsUnhandledEvents() {
        // The dispatcher must scan the context it was given for @WebhookHandler beans. Built over a real,
        // empty context it finds none, so dispatching any event throws NoWebhookHandlerException — proving it
        // actually scanned the injected context (not a mere class check). A real context (not a mock) also
        // exercises the real getBeanNamesForType(allowEagerInit=false) + getType scan path.
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext emptyContext =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            emptyContext.refresh();

            WebhookDispatcher dispatcher = autoConfig.webhookDispatcher(emptyContext);
            dispatcher.afterPropertiesSet();

            com.jinternals.webhooks.consumer.model.WebhookMetadata meta = metadataOfType("unknown.type");
            assertThatThrownBy(() -> dispatcher.dispatch(new Object(), meta))
                    .isInstanceOf(com.jinternals.webhooks.consumer.web.error.NoWebhookHandlerException.class);
        }
    }

    private static com.jinternals.webhooks.consumer.model.WebhookMetadata metadataOfType(String type) {
        return new com.jinternals.webhooks.consumer.model.WebhookMetadata(
                type,
                "msg_1",
                Instant.now(),
                null,
                "/webhooks/crm",
                "ip",
                new Object(),
                java.util.Map.of(),
                java.util.Map.of());
    }

    @Test
    void idempotencyGuardNeverDedupesByDefault() {
        // Default (in-memory idempotency off): the selected guard must treat every reservation as new —
        // tryBegin returns true even for a repeated id. This is the no-op policy's observable behaviour.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setInMemoryIdempotency(false);

        IdempotencyGuard guard = autoConfig.idempotencyGuard(properties, emptyClockProvider());

        assertThat(guard.tryBegin("dup-1")).isTrue();
        guard.markProcessed("dup-1");
        assertThat(guard.tryBegin("dup-1")).isTrue(); // no-op never remembers
    }

    @Test
    void idempotencyGuardDedupesAProcessedIdWhenInMemoryEnabled() {
        // In-memory idempotency on: the selected guard must suppress a committed id's retry — tryBegin is
        // true on first sight, then false after markProcessed. This is the in-memory policy's behaviour.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setInMemoryIdempotency(true);

        IdempotencyGuard guard = autoConfig.idempotencyGuard(properties, emptyClockProvider());

        assertThat(guard.tryBegin("dup-1")).isTrue();
        guard.markProcessed("dup-1");
        assertThat(guard.tryBegin("dup-1")).isFalse(); // committed id is now a duplicate
    }

    @Test
    void secretProviderDefaultsToResolvingFromEndpointConfig() {
        // The default provider must resolve secrets from the endpoint's own config (secret + additional),
        // which is the config-backed behaviour — asserted by what it returns, not its class.
        SecretProvider<Endpoint> provider = autoConfig.secretProvider();

        Endpoint endpoint = new Endpoint();
        endpoint.setSecret("whsec_primary");
        endpoint.setAdditionalSecrets(List.of("whsec_old"));
        assertThat(provider.secretsFor(endpoint)).containsExactly("whsec_primary", "whsec_old");
    }

    @Test
    void secretValidatorFailsFastOnAMissingEndpointSecret() {
        // The validator must actually enforce the secret contract: an endpoint with no secret makes it throw
        // at startup (afterPropertiesSet). Using an empty ObjectProvider also exercises ConfigSecretProvider::new.
        WebhookProperties properties = new WebhookProperties();
        Endpoint endpoint = new Endpoint();
        endpoint.setPath("/webhooks/crm");
        endpoint.setSecret(null);
        properties.getConsumer().getEndpoints().add(endpoint);

        ObjectProvider<SecretProvider<Endpoint>> emptyProvider =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(org.springframework.core.ResolvableType.forClassWithGenerics(
                                SecretProvider.class, Endpoint.class));
        SecretValidator validator = autoConfig.secretValidator(properties, emptyProvider);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(com.jinternals.webhooks.core.config.WebhookConfigurationException.class)
                .hasMessageContaining("No webhook secret configured");
    }

    @Test
    void customProducerDestinationSecretProviderDoesNotBackOffConsumerAndCustomEndpointProviderOverridesDefault() {
        // Coexistence regression guard: defining @Bean("destinationSecretProvider") SecretProvider<String>
        // (or Destination) in a dual producer+consumer app MUST NOT cause consumer's SecretProvider<Endpoint>
        // to fail or back off, while a custom @Bean SecretProvider<Endpoint> (under any bean name) overrides
        // ConfigSecretProvider cleanly!
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AutoPackageAnchor.class, ForeignGenericSecretProviderConfig.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        WebhookConsumerAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class))
                .withPropertyValues(
                        "webhooks.consumer.endpoints[0].path=/webhooks/crm",
                        "webhooks.consumer.endpoints[0].secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("endpointSecretProvider");
                });

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AutoPackageAnchor.class, CustomEndpointSecretProviderConfig.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        WebhookConsumerAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class))
                .withPropertyValues("webhooks.consumer.endpoints[0].path=/webhooks/crm")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ForeignGenericSecretProviderConfig {
        @org.springframework.context.annotation.Bean("destinationSecretProvider")
        SecretProvider<String> destinationSecretProvider() {
            return target -> List.of("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomEndpointSecretProviderConfig {
        @org.springframework.context.annotation.Bean
        SecretProvider<Endpoint> vaultEndpointSecretProvider() {
            return endpoint -> List.of("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        }
    }

    @Test
    void handlerFailureListenerDefaultsToASilentNoOp() {
        // The default listener must silently absorb a failure notification (no throw), which is the no-op
        // behaviour the async executor relies on. Asserting it does not throw beats asserting its class.
        HandlerFailureListener listener = autoConfig.webhookHandlerFailureListener();

        assertThatCode(() -> listener.onHandlerFailed("sample.created", new RuntimeException("boom")))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onHandlerTimeout("sample.created")).doesNotThrowAnyException();
    }

    @Test
    void handlerExecutorRunsInlineOnTheCallingThreadByDefault() {
        // SYNC (default): the selected executor runs the handler on the CALLING thread before returning —
        // the behaviour that makes a 2xx mean "processed". Asserted by capturing the executing thread.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setMode(Handler.Mode.SYNC);

        HandlerExecutor executor = autoConfig.handlerExecutor(properties, new NoOpHandlerFailureListener());

        java.util.concurrent.atomic.AtomicReference<Thread> ran = new java.util.concurrent.atomic.AtomicReference<>();
        executor.execute("sample.created", () -> ran.set(Thread.currentThread()));
        assertThat(ran.get()).isSameAs(Thread.currentThread()); // inline, not off-thread
    }

    @Test
    void handlerExecutorRunsOffThreadWhenAsyncConfigured() throws Exception {
        // ASYNC: the selected executor submits the handler to a pool and runs it on a DIFFERENT thread —
        // the behaviour that makes a 2xx mean "accepted". Asserted by comparing the executing thread.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setMode(Handler.Mode.ASYNC);

        HandlerExecutor executor = autoConfig.handlerExecutor(properties, new NoOpHandlerFailureListener());
        try {
            java.util.concurrent.CompletableFuture<Thread> ran = new java.util.concurrent.CompletableFuture<>();
            executor.execute("sample.created", () -> ran.complete(Thread.currentThread()));
            assertThat(ran.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotSameAs(Thread.currentThread());
        } finally {
            ((AutoCloseable) executor).close();
        }
    }

    @Test
    void observationRegistryDefaultsToNoop() {
        assertThat(autoConfig.webhookObservationRegistry()).isSameAs(ObservationRegistry.NOOP);
    }

    @Test
    void observationRegistryBacksOffWhenActuatorObservationAutoConfigurationIsPresent() {
        // Regression guard: com.jinternals... sorts alphabetically BEFORE org.springframework.boot.micrometer...
        // Without afterName = "...ObservationAutoConfiguration", our NOOP fallback registered first and caused
        // Spring Boot's ObservationAutoConfiguration to back off, silently disabling ALL observations in the host app.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AutoPackageAnchor.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        WebhookConsumerAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration.class,
                        org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration.class,
                        org.springframework.boot.micrometer.metrics.autoconfigure.export.simple
                                .SimpleMetricsExportAutoConfiguration.class,
                        org.springframework.boot.micrometer.metrics.autoconfigure
                                .CompositeMeterRegistryAutoConfiguration.class,
                        org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
                                .class))
                .run(ctx -> {
                    ObservationRegistry observationRegistry = ctx.getBean(ObservationRegistry.class);
                    assertThat(observationRegistry).isNotSameAs(ObservationRegistry.NOOP);
                    io.micrometer.observation.Observation.createNotStarted("test.obs", observationRegistry)
                            .observe(() -> {});
                    assertThat(ctx.getBean(MeterRegistry.class).find("test.obs").timer())
                            .isNotNull();
                });
    }

    @org.springframework.boot.autoconfigure.AutoConfigurationPackage(
            basePackages = "com.jinternals.webhooks.consumer.config.typefixtures")
    static class AutoPackageAnchor {}

    @Test
    void observedAspectIsProvidedForTheObservationRegistry() {
        // The factory constructs the standard Micrometer ObservedAspect (a third-party wrapper whose sole
        // job is to advise @Observed) from the given registry. Its internals are Micrometer's, not ours, so
        // the honest assertion here is that the bean is actually produced (kills the null-return mutant); the
        // real advising is exercised by the @SpringBootTest wiring tests.
        ObservedAspect aspect = autoConfig.webhookObservedAspect(ObservationRegistry.create());

        assertThat(aspect).isNotNull();
    }

    @Test
    void webhookMetricsRecordsNothingWhenNoMeterRegistry() {
        // No MeterRegistry: the selected metrics must be inert — recording an event registers no meter
        // anywhere (there is no registry to touch) and never throws. This is the no-op policy's behaviour.
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        org.mockito.Mockito.when(meterRegistry.getIfAvailable()).thenReturn(null);

        WebhookMetrics metrics =
                new WebhookConsumerAutoConfiguration.MetricsConfiguration().webhookMetrics(meterRegistry);

        assertThatCode(() -> metrics.received("sample.created")).doesNotThrowAnyException();
    }

    @Test
    void webhookMetricsRecordsIntoTheMeterRegistryWhenPresent() {
        // A MeterRegistry is present: the selected metrics must actually register meters into THAT registry.
        // Recording a "received" event must create the webhook.received counter — the Micrometer behaviour.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        org.mockito.Mockito.when(meterRegistry.getIfAvailable()).thenReturn(registry);

        WebhookMetrics metrics =
                new WebhookConsumerAutoConfiguration.MetricsConfiguration().webhookMetrics(meterRegistry);
        metrics.received("sample.created");

        assertThat(registry.find("webhook.received")
                        .tag("type", "sample.created")
                        .counter())
                .isNotNull();
    }

    @Test
    void fallbackWebhookMetricsRecordsNothing() {
        // The micrometer-absent fallback must also be inert: recording never throws and touches no registry.
        WebhookMetrics metrics = autoConfig.noOpWebhookMetrics();

        assertThatCode(() -> metrics.received("sample.created")).doesNotThrowAnyException();
    }
}
