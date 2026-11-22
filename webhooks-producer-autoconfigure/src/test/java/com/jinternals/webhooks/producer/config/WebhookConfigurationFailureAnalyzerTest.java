package com.jinternals.webhooks.producer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Unit test for the producer {@link WebhookConfigurationFailureAnalyzer}: it renders a
 * {@link WebhookConfigurationException}'s description and action as the Spring Boot startup-failure report's
 * Description and Action, preserving the cause.
 */
class WebhookConfigurationFailureAnalyzerTest {

    @Test
    void rendersDescriptionAndActionFromTheException() {
        WebhookConfigurationException cause = new WebhookConfigurationException(
                "No signing secret configured for webhook destination 'crm'",
                "Set webhooks.producer.destinations.crm.secret to a whsec_ or whsk_ value.");

        FailureAnalysis analysis = new WebhookConfigurationFailureAnalyzer().analyze(cause, cause);

        assertThat(analysis.getDescription()).isEqualTo(cause.getMessage()).contains("crm");
        assertThat(analysis.getAction())
                .isEqualTo(cause.getAction())
                .contains("webhooks.producer.destinations.crm.secret");
        assertThat(analysis.getCause()).isSameAs(cause);
    }
}
