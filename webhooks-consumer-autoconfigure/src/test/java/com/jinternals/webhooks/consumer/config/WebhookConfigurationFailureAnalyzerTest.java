package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Unit test for the consumer {@link WebhookConfigurationFailureAnalyzer}: it renders a
 * {@link WebhookConfigurationException}'s description and action as the Spring Boot startup-failure report's
 * Description and Action, preserving the cause.
 */
class WebhookConfigurationFailureAnalyzerTest {

    @Test
    void rendersDescriptionAndActionFromTheException() {
        WebhookConfigurationException cause = new WebhookConfigurationException(
                "No webhook secret configured for endpoint /webhooks",
                "Set the secret for this endpoint to a whsec_ HMAC secret (v1) or a whpk_ Ed25519 public key.");

        FailureAnalysis analysis = new WebhookConfigurationFailureAnalyzer().analyze(cause, cause);

        assertThat(analysis.getDescription()).isEqualTo(cause.getMessage()).contains("/webhooks");
        assertThat(analysis.getAction()).isEqualTo(cause.getAction()).contains("whpk_");
        assertThat(analysis.getCause()).isSameAs(cause);
    }
}
