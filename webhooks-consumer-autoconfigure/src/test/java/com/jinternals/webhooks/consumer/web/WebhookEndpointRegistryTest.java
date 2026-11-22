package com.jinternals.webhooks.consumer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.support.LogCapture;
import java.lang.reflect.Method;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Unit test for {@link WebhookEndpointRegistry}: at startup it registers exactly one POST mapping per
 * configured endpoint path, all pointing at the generic controller's {@code handle} method (SPEC.md §7).
 */
class WebhookEndpointRegistryTest {

    private Endpoint endpoint(String path, List<String> types) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath(path);
        endpoint.setTypes(types);
        return endpoint;
    }

    @Test
    void registersOnePostMappingPerConfiguredEndpoint() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        WebhookReceiveController controller = mock(WebhookReceiveController.class);
        WebhookProperties properties = new WebhookProperties();
        // Two endpoints exercising BOTH branches of the "[ALL]" vs explicit-types log line (L55):
        // one accept-all (empty types) and one type-scoped.
        properties
                .getConsumer()
                .setEndpoints(List.of(
                        endpoint("/webhooks/crm", List.of()), endpoint("/webhooks/billing", List.of("invoice.paid"))));

        WebhookEndpointRegistry registry = new WebhookEndpointRegistry(handlerMapping, controller, properties);
        try (LogCapture logs = LogCapture.forClass(WebhookEndpointRegistry.class)) {
            registry.registerEndpoints();

            // The accept-all endpoint (empty types) renders "[ALL]"; the type-scoped one renders its
            // explicit list. Asserting both kills the mutant that forces the isEmpty() ternary to a
            // constant branch (L55).
            Assertions.assertThat(logs.formattedMessages())
                    .anySatisfy(line -> Assertions.assertThat(line)
                            .contains("POST /webhooks/crm")
                            .contains("types [ALL]"))
                    .anySatisfy(line -> Assertions.assertThat(line)
                            .contains("POST /webhooks/billing")
                            .contains("types [invoice.paid]"));
        }

        Method handle = org.springframework.util.ReflectionUtils.findMethod(
                WebhookReceiveController.class, "handle", jakarta.servlet.http.HttpServletRequest.class, String.class);
        verify(handlerMapping, times(2)).registerMapping(any(RequestMappingInfo.class), eq(controller), eq(handle));
    }

    @Test
    void registersNothingWhenNoEndpointsConfigured() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        WebhookReceiveController controller = mock(WebhookReceiveController.class);

        new WebhookEndpointRegistry(handlerMapping, controller, new WebhookProperties()).registerEndpoints();

        verifyNoInteractions(handlerMapping);
    }
}
