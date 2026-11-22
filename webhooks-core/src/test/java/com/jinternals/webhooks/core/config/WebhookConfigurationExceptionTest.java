package com.jinternals.webhooks.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link WebhookConfigurationException}: it carries both a description (its message) and a
 * concrete remediation action, and preserves an optional cause — the two pieces a {@code FailureAnalyzer}
 * renders as the <em>Description</em> and <em>Action</em> of a clean startup-failure report.
 */
class WebhookConfigurationExceptionTest {

    @Test
    void exposesDescriptionAsMessageAndActionSeparately() {
        WebhookConfigurationException ex = new WebhookConfigurationException("what is wrong", "how to fix it");

        assertThat(ex.getMessage()).isEqualTo("what is wrong");
        assertThat(ex.getAction()).isEqualTo("how to fix it");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void preservesTheCauseWhenGiven() {
        IllegalArgumentException cause = new IllegalArgumentException("boom");
        WebhookConfigurationException ex = new WebhookConfigurationException("what is wrong", "how to fix it", cause);

        assertThat(ex.getMessage()).isEqualTo("what is wrong");
        assertThat(ex.getAction()).isEqualTo("how to fix it");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isARuntimeExceptionSoItAbortsContextRefresh() {
        assertThat(new WebhookConfigurationException("d", "a")).isInstanceOf(RuntimeException.class);
    }
}
