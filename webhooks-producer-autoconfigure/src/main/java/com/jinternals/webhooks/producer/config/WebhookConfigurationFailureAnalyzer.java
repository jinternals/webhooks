package com.jinternals.webhooks.producer.config;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a {@link WebhookConfigurationException} into a clean Spring Boot startup failure — a focused
 * description plus a concrete action — instead of a raw stack trace, so an operator sees immediately which
 * producer destination is misconfigured and how to fix it (see SPEC.md §19d, §19i). Covers a missing or
 * malformed URL, an allow-list rejection, a missing signing secret, and every {@code v2a} rule (missing or
 * dotted endpoint-id, malformed or wrong-half Ed25519 key).
 *
 * <p>Registered via {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}.
 */
public class WebhookConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<WebhookConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, WebhookConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
    }
}
