package com.jinternals.webhooks.consumer.web;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Attaches the matched {@link Endpoint} (secret, type allow-list, path) to the request so the single
 * generic {@link WebhookReceiveController} stays path-agnostic. Mirrors how {@code dynamic-rest-api}
 * injects per-endpoint config via its {@code SchemaArgumentResolver}.
 *
 * <p>Resolution is done by the <strong>registered mapping pattern</strong> that Spring MVC already
 * matched — read from {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} — not by raw
 * {@code getRequestURI()} equality. This is robust to the servlet context path, URL-encoding, and
 * trailing-slash/path-matching configuration, because the pattern is exactly the one
 * {@link WebhookEndpointRegistry} registered from {@link Endpoint#getPath()} (see SPEC.md §7).
 */
public class EndpointResolvingInterceptor implements HandlerInterceptor {

    private final Map<String, Endpoint> byPattern = new HashMap<>();

    public EndpointResolvingInterceptor(WebhookProperties properties) {
        for (Endpoint endpoint : properties.getConsumer().getEndpoints()) {
            byPattern.put(endpoint.getPath(), endpoint);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            Endpoint endpoint = byPattern.get(pattern.toString());
            if (endpoint != null) {
                request.setAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE, endpoint);
            }
        }
        return true;
    }
}
