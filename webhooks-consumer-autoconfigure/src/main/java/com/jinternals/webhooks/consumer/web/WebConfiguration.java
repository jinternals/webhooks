package com.jinternals.webhooks.consumer.web;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the raw-body argument resolver and the endpoint-resolving interceptor (SPEC.md §7).
 *
 * <p><strong>Scoped for host apps.</strong> This is a {@link WebMvcConfigurer} added by the library's
 * auto-configuration, so it participates in the host application's single shared MVC configuration
 * (see SPEC.md §14):
 * <ul>
 *   <li>The endpoint-resolving interceptor is registered only for the configured webhook listen
 *       paths, so it never runs for the host app's own requests.</li>
 *   <li>The raw-body argument resolver is inert unless a controller actually requests the raw-body
 *       parameter type, so it is harmless to the host's own controllers.</li>
 * </ul>
 */
public class WebConfiguration implements WebMvcConfigurer {

    private final WebhookProperties properties;

    public WebConfiguration(WebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RawBodyArgumentResolver(
                properties.getConsumer().getMaxBodySize().toBytes()));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> patterns = interceptorPathPatterns();
        if (!patterns.isEmpty()) {
            registry.addInterceptor(new EndpointResolvingInterceptor(properties))
                    .addPathPatterns(patterns); // scope to webhook paths; never host requests
        }
    }

    /**
     * The configured webhook listen paths the interceptor is scoped to, with null/blank paths dropped
     * (a misconfigured endpoint must not widen the interceptor to all requests). Package-private so it
     * can be asserted directly, since {@link InterceptorRegistry} is opaque to tests.
     */
    List<String> interceptorPathPatterns() {
        return properties.getConsumer().getEndpoints().stream()
                .map(Endpoint::getPath)
                .filter(p -> p != null && !p.isBlank())
                .toList();
    }
}
