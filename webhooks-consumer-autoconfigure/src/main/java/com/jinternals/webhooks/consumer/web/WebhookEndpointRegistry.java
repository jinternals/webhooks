package com.jinternals.webhooks.consumer.web;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers one POST mapping per configured path at startup, all pointing at the single generic
 * {@link WebhookReceiveController#handle} method (see SPEC.md §7). Adapted from
 * {@code dynamic-rest-api}'s {@code DynamicEndpointRegistry}.
 */
public class WebhookEndpointRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointRegistry.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final WebhookReceiveController controller;
    private final WebhookProperties properties;

    public WebhookEndpointRegistry(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            WebhookReceiveController controller,
            WebhookProperties properties) {
        this.handlerMapping = handlerMapping;
        this.controller = controller;
        this.properties = properties;
    }

    @PostConstruct
    public void registerEndpoints() {
        // handle(...) is a declared method of this same class, so findMethod never returns null;
        // requireNonNull documents the invariant without an unreachable, uncoverable branch.
        Method handle = java.util.Objects.requireNonNull(
                ReflectionUtils.findMethod(
                        WebhookReceiveController.class, "handle", HttpServletRequest.class, String.class),
                "WebhookReceiveController.handle(...) not found");

        for (Endpoint endpoint : properties.getConsumer().getEndpoints()) {
            RequestMappingInfo.Builder builder =
                    RequestMappingInfo.paths(endpoint.getPath()).methods(RequestMethod.POST);
            RequestMappingInfo.BuilderConfiguration config = handlerMapping.getBuilderConfiguration();
            if (config != null) {
                builder.options(config);
            }
            handlerMapping.registerMapping(builder.build(), controller, handle);
            log.info(
                    "Registered webhook endpoint POST {} accepting types {}",
                    endpoint.getPath(),
                    endpoint.getTypes().isEmpty() ? "[ALL]" : endpoint.getTypes());
        }
    }
}
