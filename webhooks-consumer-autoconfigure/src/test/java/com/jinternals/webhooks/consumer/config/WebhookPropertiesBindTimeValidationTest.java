package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves the {@code @Validated} constraints on {@link WebhookProperties} fire during real Spring Boot
 * <strong>property binding</strong> — i.e. an invalid {@code webhooks.*} value fails the application context
 * at startup, not merely when a raw Jakarta {@link jakarta.validation.Validator} is invoked by hand (SPEC
 * §14a). This complements {@link WebhookPropertiesValidationTest}, which asserts the constraint set itself.
 */
class WebhookPropertiesBindTimeValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesHolder.class);

    @EnableConfigurationProperties(WebhookProperties.class)
    static class PropertiesHolder {}

    @Test
    void contextStartsWithValidDefaults() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void contextFailsWhenCorePoolSizeExceedsMaxPoolSize() {
        runner.withPropertyValues(
                        "webhooks.consumer.handler.core-pool-size=10", "webhooks.consumer.handler.max-pool-size=4")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("core-pool-size must not exceed max-pool-size"));
    }

    @Test
    void contextFailsWhenMaxBodySizeIsZero() {
        runner.withPropertyValues("webhooks.consumer.max-body-size=0B")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("max-body-size must be positive"));
    }

    @Test
    void contextFailsWhenHandlerTimeoutIsZero() {
        runner.withPropertyValues("webhooks.consumer.handler.timeout=0s")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("timeout must be positive"));
    }

    @Test
    void contextFailsWhenAnEndpointPathIsNotAbsolute() {
        runner.withPropertyValues("webhooks.consumer.endpoints[0].path=webhooks/crm")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("absolute path"));
    }
}
