package com.jinternals.webhooks.consumer.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebConfigurationTest {

    private Endpoint endpoint(String path) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath(path);
        return endpoint;
    }

    @Test
    void scopesInterceptorToConfiguredPathsAndDropsNullOrBlank() {
        WebhookProperties properties = new WebhookProperties();
        // One valid, one null, one blank: the interceptor must be scoped to the valid path only, so a
        // misconfigured (null/blank) endpoint can't widen it to every request in the host app.
        properties.getConsumer().setEndpoints(List.of(endpoint("/webhooks/crm"), endpoint(null), endpoint("   ")));

        WebConfiguration config = new WebConfiguration(properties);

        assertThat(config.interceptorPathPatterns()).containsExactly("/webhooks/crm");
    }

    @Test
    void returnsEmptyWhenNoEndpointsConfigured() {
        WebConfiguration config = new WebConfiguration(new WebhookProperties());
        assertThat(config.interceptorPathPatterns()).isEmpty();
    }

    @Test
    void addInterceptorsSkipsRegistrationWhenEndpointsEmptyAndScopesWhenPresent() {
        class ExposingInterceptorRegistry
                extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
            List<Object> exposed() {
                return super.getInterceptors();
            }
        }

        ExposingInterceptorRegistry emptyRegistry = new ExposingInterceptorRegistry();
        new WebConfiguration(new WebhookProperties()).addInterceptors(emptyRegistry);
        assertThat(emptyRegistry.exposed()).isEmpty();

        WebhookProperties withEndpoint = new WebhookProperties();
        withEndpoint.getConsumer().setEndpoints(List.of(endpoint("/webhooks/crm")));
        ExposingInterceptorRegistry activeRegistry = new ExposingInterceptorRegistry();
        new WebConfiguration(withEndpoint).addInterceptors(activeRegistry);
        assertThat(activeRegistry.exposed()).hasSize(1);
    }
}
