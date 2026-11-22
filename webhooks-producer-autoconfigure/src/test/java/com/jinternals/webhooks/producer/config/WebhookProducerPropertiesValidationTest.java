package com.jinternals.webhooks.producer.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates that the {@code @Validated} constraints on {@link WebhookProducerProperties} reject invalid
 * configuration (SPEC §19). These are the constraints Spring runs at bind time; a null HTTP timeout is
 * caught here rather than surfacing later when the RestClient is built.
 */
class WebhookProducerPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(new WebhookProducerProperties())).isEmpty();
    }

    @Test
    void rejectsNullConnectTimeout() {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getHttp().setConnectTimeout(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("connectTimeout"));
    }

    @Test
    void rejectsNullReadTimeout() {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getHttp().setReadTimeout(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("readTimeout"));
    }

    @Test
    void acceptsCustomNonNullTimeouts() {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getHttp().setConnectTimeout(Duration.ofSeconds(2));
        properties.getHttp().setReadTimeout(Duration.ofSeconds(3));
        properties.getRetry().setInitialBackoff(Duration.ofMillis(250));
        properties.getRetry().setMaxBackoff(Duration.ofSeconds(2));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsNullInitialBackoff() {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getRetry().setInitialBackoff(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("initialBackoff"));
    }

    @Test
    void rejectsNullMaxBackoff() {
        WebhookProducerProperties properties = new WebhookProducerProperties();
        properties.getRetry().setMaxBackoff(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("maxBackoff"));
    }
}
