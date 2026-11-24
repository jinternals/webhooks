package com.jinternals.webhooks.producer.config;

import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.client.WebhookPublisher;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.secret.ConfigDestinationSecretProvider;
import com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.AllowListDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.sign.DefaultWebhookSigner;
import com.jinternals.webhooks.producer.sign.NoOpWebhookSigner;
import com.jinternals.webhooks.producer.sign.WebhookSigner;
import com.jinternals.webhooks.producer.transport.InterceptingWebhookTransport;
import com.jinternals.webhooks.producer.transport.NoOpWebhookDeliveryListener;
import com.jinternals.webhooks.producer.transport.RestClientWebhookTransport;
import com.jinternals.webhooks.producer.transport.RetryingWebhookTransport;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auto-configuration for the Standard Webhooks producer (see SPEC.md §19). An existing Spring Boot
 * application only needs the {@code webhooks-producer} dependency, {@code webhooks.producer.*}
 * properties, {@code @EnableWebhookClients}, and its {@code @WebhookClient} interfaces.
 *
 * <p><strong>Drop-in by design.</strong> Like the consumer, the producer never component-scans; every
 * bean is an explicit {@code @Bean} guarded by {@link ConditionalOnMissingBean}, so the host can
 * override any piece — {@link WebhookSigner}, {@link WebhookTransport},
 * {@code SecretProvider<Destination>}, even the {@link RestClient} the default transport uses.
 */
@AutoConfiguration(
        after = {JacksonAutoConfiguration.class, RestClientAutoConfiguration.class},
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@EnableConfigurationProperties(WebhookProducerProperties.class)
public class WebhookProducerAutoConfiguration {

    /**
     * Underlying JDK {@link HttpClient} for the default transport, with connect timeout applied so a stalled
     * destination cannot pin the calling thread (SPEC §19c), <strong>redirect following disabled</strong>
     * ({@link HttpClient.Redirect#NEVER}), and {@code destroyMethod = "close"} so internal selector/worker
     * threads are shut down when the application context closes. Standard Webhooks treats a {@code 3xx} as a
     * delivery failure ("don't follow redirects — update the webhook URL instead"), so the client must surface
     * the redirect status to {@link RestClientWebhookTransport} rather than quietly chase it to another host
     * (which would also bypass the destination URL allow-list).
     */
    @Bean(name = "webhookProducerHttpClient", defaultCandidate = false, destroyMethod = "close")
    @ConditionalOnMissingBean(name = {"webhookProducerRestClient", "webhookProducerHttpClient"})
    public HttpClient webhookProducerHttpClient(WebhookProducerProperties properties) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * {@link RestClient} used by the default transport, cloned from the host's {@link RestClient.Builder} (so
     * Micrometer HTTP client observation/tracing customizers propagate automatically) with read timeout applied
     * via {@link JdkClientHttpRequestFactory}. Marked {@code defaultCandidate = false} so it never collides with
     * unqualified {@code @Autowired RestClient} injection in host services; override by defining a
     * {@code webhookProducerRestClient} bean.
     */
    @Bean(name = "webhookProducerRestClient", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "webhookProducerRestClient")
    public RestClient webhookProducerRestClient(
            ObjectProvider<RestClient.Builder> builderProvider,
            @Qualifier("webhookProducerHttpClient") HttpClient webhookProducerHttpClient,
            WebhookProducerProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(webhookProducerHttpClient);
        requestFactory.setReadTimeout(properties.getHttp().getReadTimeout());
        return builderProvider
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Destination-URL guard (SSRF protection; SPEC §19d). <strong>Permissive by default</strong>
     * ({@link AllowAllDestinationUrlValidator}); when {@code webhooks.producer.security.allowed-hosts} is
     * non-empty it becomes an {@link AllowListDestinationUrlValidator}. Override with a
     * {@code @Bean DestinationUrlValidator} for a custom policy.
     */
    @Bean
    @ConditionalOnMissingBean
    public DestinationUrlValidator webhookDestinationUrlValidator(WebhookProducerProperties properties) {
        var allowedHosts = properties.getSecurity().getAllowedHosts();
        return allowedHosts.isEmpty()
                ? new AllowAllDestinationUrlValidator()
                : new AllowListDestinationUrlValidator(allowedHosts);
    }

    /**
     * Dead-letter / failure hook (SPEC §20). Notified once when a delivery is abandoned (fatal {@code 4xx}
     * or retries exhausted), before the exception is rethrown. <strong>No-op by default</strong> so the
     * producer stays drop-in; publish a {@code @Bean WebhookDeliveryListener} to persist/alert on failures.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookDeliveryListener webhookDeliveryListener() {
        return new NoOpWebhookDeliveryListener();
    }

    /**
     * Default transport chain (SPEC §19, §19b): a {@link RestClientWebhookTransport} (POST + URL guard),
     * wrapped in an {@link InterceptingWebhookTransport} (applies any {@link WebhookRequestInterceptor}
     * beans — the seam for transport auth such as an OAuth2 bearer token), wrapped in a
     * {@link RetryingWebhookTransport} (in-process exponential backoff + jitter on retryable failures).
     * Retry defaults to {@code max-attempts=3}; set it to {@code 1} to disable. An abandoned delivery is
     * routed to the {@link WebhookDeliveryListener} before it is rethrown (SPEC §20).
     *
     * <p>Interceptors sit <strong>inside</strong> the retry layer, so an interceptor-added header derived
     * from a short-lived credential is re-derived on every retryable attempt (if a token expires during
     * backoff, the next attempt picks up a fresh one). With no interceptor beans the intercepting layer is a
     * transparent pass-through.
     *
     * <p>Override with your own {@code @Bean WebhookTransport} only to change the <em>delivery
     * mechanism</em> (e.g. send asynchronously or through a queue) — to add transport auth, publish a
     * {@link WebhookRequestInterceptor} instead and keep the built-in retry and SSRF guard.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookTransport webhookTransport(
            @Qualifier("webhookProducerRestClient") RestClient webhookProducerRestClient,
            DestinationUrlValidator urlValidator,
            WebhookDeliveryListener deliveryListener,
            WebhookProducerProperties properties,
            ObjectProvider<WebhookRequestInterceptor> interceptors,
            ObjectProvider<Clock> clockProvider) {
        RestClientWebhookTransport base = new RestClientWebhookTransport(
                webhookProducerRestClient, urlValidator, clockProvider.getIfAvailable(Clock::systemUTC));
        InterceptingWebhookTransport intercepting = new InterceptingWebhookTransport(
                base, interceptors.orderedStream().toList());
        return new RetryingWebhookTransport(intercepting, urlValidator, deliveryListener, properties.getRetry());
    }

    /**
     * Message-signing policy (SPEC §19, §19f, §19i). <strong>On by default</strong>: a
     * {@link DefaultWebhookSigner} adds a Standard Webhooks {@code webhook-signature} header, selecting the
     * scheme per secret by prefix — {@code whsec_} for symmetric {@code v1} (HMAC-SHA256) or {@code whsk_}
     * for asymmetric {@code v2a} (Ed25519). When {@code webhooks.producer.signing-enabled=false} it becomes a
     * {@link NoOpWebhookSigner} and deliveries go out unsigned (no secret required). Override with a
     * {@code @Bean WebhookSigner} for a different scheme.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookSigner webhookSigner(WebhookProducerProperties properties) {
        return properties.isSigningEnabled() ? new DefaultWebhookSigner() : new NoOpWebhookSigner();
    }

    /**
     * Default destination secret source: Spring config ({@code destinations.<name>.secret}).
     *
     * <p>Registered as {@code defaultCandidate = false} and guarded by bean name rather than the raw
     * {@code SecretProvider} type so it never collides with the consumer starter's
     * {@code SecretProvider<Endpoint>} when both libraries coexist in one application. Consumers resolve
     * {@code ObjectProvider<SecretProvider<Destination>>} (full generic type matching, preferring any
     * host-defined {@code SecretProvider<Destination>} regardless of bean name and falling back to
     * {@link ConfigDestinationSecretProvider}), while explicit {@code @Qualifier("destinationSecretProvider")}
     * injection remains supported.
     */
    @Bean(name = "destinationSecretProvider", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "destinationSecretProvider")
    public SecretProvider<Destination> destinationSecretProvider() {
        return new ConfigDestinationSecretProvider();
    }

    /**
     * Validates every configured destination at startup (URL parseable + host + allow-list policy, and at
     * least one signing secret) so a producer misconfiguration fails at boot, not on the first business
     * event (SPEC §19d). Disable with {@code webhooks.producer.validate-destinations-on-startup=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    public DestinationValidator webhookDestinationValidator(
            WebhookProducerProperties properties,
            DestinationUrlValidator urlValidator,
            ObjectProvider<SecretProvider<Destination>> destinationSecretProvider) {
        return new DestinationValidator(
                properties,
                urlValidator,
                destinationSecretProvider.getIfAvailable(ConfigDestinationSecretProvider::new));
    }

    /** The engine behind every {@code @WebhookClient} proxy: build + sign + send (SPEC §19). */
    @Bean
    @ConditionalOnMissingBean
    public WebhookPublisher webhookPublisher(
            JsonMapper jsonMapper,
            WebhookSigner signer,
            WebhookTransport transport,
            ObjectProvider<SecretProvider<Destination>> destinationSecretProvider,
            WebhookProducerProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new WebhookPublisher(
                jsonMapper,
                signer,
                transport,
                destinationSecretProvider.getIfAvailable(ConfigDestinationSecretProvider::new),
                properties,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Inject-safe fallback {@link ObservationRegistry} (SPEC §13). Under the standard Spring stack the host
     * app's actuator provides a real registry (metrics via its {@code MeterRegistry}, traces if a tracer
     * bridge is present), and that one is used. Absent that, this resolves to {@link ObservationRegistry#NOOP}
     * so {@code @Observed} on {@code publish} is always satisfiable. Disable the standard way:
     * {@code management.observations.enable.webhook.send=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry webhookObservationRegistry() {
        return ObservationRegistry.NOOP;
    }

    /**
     * Enables {@link io.micrometer.observation.annotation.Observed @Observed} on the proxied
     * {@link WebhookPublisher#publish} via the standard Micrometer {@link ObservedAspect}. Requires AOP on
     * the classpath; {@link ConditionalOnClass} keeps this a soft dependency.
     */
    @Bean
    @ConditionalOnClass(name = {"org.aspectj.lang.ProceedingJoinPoint", "org.aspectj.weaver.Advice"})
    @ConditionalOnMissingBean
    public ObservedAspect webhookObservedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
