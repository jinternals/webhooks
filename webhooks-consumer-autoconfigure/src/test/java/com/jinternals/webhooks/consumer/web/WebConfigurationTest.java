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
    void scopesInterceptorToConfiguredPathsIncludingTrailingSlashVariantAndDropsNullOrBlank() {
        WebhookProperties properties = new WebhookProperties();
        // One normal path, one already ending with '/', one null, one blank: the interceptor scopes to exact +
        // trailing-slash variants without doubling '//', and drops null/blank endpoints.
        properties
                .getConsumer()
                .setEndpoints(List.of(
                        endpoint("/webhooks/crm"), endpoint("/webhooks/billing/"), endpoint(null), endpoint("   ")));

        WebConfiguration config = new WebConfiguration(properties);

        assertThat(config.interceptorPathPatterns())
                .containsExactly("/webhooks/crm", "/webhooks/crm/", "/webhooks/billing/");
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
