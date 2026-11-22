package com.jinternals.webhooks.consumer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class EndpointResolvingInterceptorTest {

    private EndpointResolvingInterceptor interceptor(String path) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath(path);
        WebhookProperties props = new WebhookProperties();
        props.getConsumer().setEndpoints(List.of(endpoint));
        return new EndpointResolvingInterceptor(props);
    }

    @Test
    void attachesEndpointWhenPatternMatches() {
        EndpointResolvingInterceptor interceptor = interceptor("/webhooks/crm");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/webhooks/crm");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        Object attr = request.getAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE);
        assertThat(attr).isInstanceOf(Endpoint.class);
        assertThat(((Endpoint) attr).getPath()).isEqualTo("/webhooks/crm");
    }

    @Test
    void doesNotAttachWhenPatternUnknown() {
        EndpointResolvingInterceptor interceptor = interceptor("/webhooks/crm");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/other");
        // Pre-seed a sentinel so we can tell "left untouched" apart from "overwritten with null".
        // The mutant that forces `endpoint != null` true would overwrite the sentinel with a null
        // endpoint; asserting the sentinel survives kills it.
        Object sentinel = new Object();
        request.setAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE, sentinel);

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertThat(request.getAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE))
                .isSameAs(sentinel);
    }

    @Test
    void doesNotAttachWhenNoPatternAttribute() {
        EndpointResolvingInterceptor interceptor = interceptor("/webhooks/crm");
        MockHttpServletRequest request = new MockHttpServletRequest();

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertThat(request.getAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE))
                .isNull();
    }

    @Test
    void rejectsDuplicateEndpointPathsDefensively() {
        Endpoint first = new Endpoint();
        first.setPath("/webhooks/crm");
        Endpoint second = new Endpoint();
        second.setPath("/webhooks/crm");
        WebhookProperties props = new WebhookProperties();
        props.getConsumer().setEndpoints(List.of(first, second));

        assertThatThrownBy(() -> new EndpointResolvingInterceptor(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate webhook endpoint path configured: /webhooks/crm");
    }
}
