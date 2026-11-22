package com.jinternals.webhooks.consumer.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates that the {@code @Validated} constraints on {@link WebhookProperties} actually reject invalid
 * configuration (SPEC §14a). These are the same constraints Spring runs at bind time; asserting them here
 * proves a non-positive pool size, a null timeout, or a non-absolute endpoint path is caught rather than
 * surfacing later as a raw executor/runtime error.
 */
class WebhookPropertiesValidationTest {

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
        assertThat(validator.validate(new WebhookProperties())).isEmpty();
    }

    @Test
    void rejectsNonPositiveCorePoolSize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setCorePoolSize(0);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("corePoolSize"));
    }

    @Test
    void rejectsNonPositiveMaxPoolSize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setMaxPoolSize(-1);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("maxPoolSize"));
    }

    @Test
    void rejectsNonPositiveQueueCapacity() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setQueueCapacity(0);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("queueCapacity"));
    }

    @Test
    void rejectsNullHandlerTimeout() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setTimeout(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("timeout"));
    }

    @Test
    void rejectsNullReplayTolerance() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setReplayTolerance(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("replayTolerance"));
    }

    @Test
    void rejectsBlankEndpointPath() {
        WebhookProperties properties = new WebhookProperties();
        Endpoint endpoint = new Endpoint();
        endpoint.setPath("  ");
        properties.getConsumer().getEndpoints().add(endpoint);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("path"));
    }

    @Test
    void rejectsNonAbsoluteEndpointPath() {
        WebhookProperties properties = new WebhookProperties();
        Endpoint endpoint = new Endpoint();
        endpoint.setPath("webhooks/crm"); // no leading '/'
        properties.getConsumer().getEndpoints().add(endpoint);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("path"));
    }

    @Test
    void acceptsAnAbsoluteEndpointPath() {
        WebhookProperties properties = new WebhookProperties();
        Endpoint endpoint = new Endpoint();
        endpoint.setPath("/webhooks/crm");
        endpoint.setSecret("whsec_abc");
        properties.getConsumer().getEndpoints().add(endpoint);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void validatesNestedDurationAsPresent() {
        // A valid non-null custom tolerance passes (pins that @NotNull is satisfied by a real value, not
        // only by the default).
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setReplayTolerance(Duration.ofMinutes(2));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsCorePoolSizeGreaterThanMaxPoolSize() {
        // core > max is only rejected by ThreadPoolExecutor when the pool is first built (lazily); the
        // class-level @AssertTrue catches it at bind time instead.
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setCorePoolSize(10);
        properties.getConsumer().getHandler().setMaxPoolSize(4);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("poolSizingValid"));
    }

    @Test
    void acceptsCorePoolSizeEqualToMaxPoolSize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setCorePoolSize(4);
        properties.getConsumer().getHandler().setMaxPoolSize(4);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsZeroHandlerTimeout() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setTimeout(Duration.ZERO);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("timeoutPositive"));
    }

    @Test
    void rejectsNegativeHandlerTimeout() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().getHandler().setTimeout(Duration.ofSeconds(-1));

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("timeoutPositive"));
    }

    @Test
    void rejectsNonPositiveMaxBodySize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setMaxBodySize(org.springframework.util.unit.DataSize.ofBytes(0));

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("maxBodySizePositive"));
    }

    @Test
    void rejectsNullMaxBodySize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setMaxBodySize(null);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("maxBodySizePositive"));
    }

    @Test
    void rejectsNonPositiveIdempotencyMaxSize() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setInMemoryIdempotencyMaxSize(0);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("inMemoryIdempotencyMaxSize"));
    }

    @Test
    void rejectsNegativeReplayTolerance() {
        WebhookProperties properties = new WebhookProperties();
        properties.getConsumer().setReplayTolerance(Duration.ofSeconds(-1));

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("replayToleranceNonNegative"));
    }

    @Test
    void acceptsMultipleDistinctEndpointPaths() {
        WebhookProperties properties = new WebhookProperties();
        Endpoint crm = new Endpoint();
        crm.setPath("/webhooks/crm");
        Endpoint billing = new Endpoint();
        billing.setPath("/webhooks/billing");
        properties.getConsumer().getEndpoints().add(crm);
        properties.getConsumer().getEndpoints().add(billing);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsDuplicateEndpointPaths() {
        WebhookProperties properties = new WebhookProperties();
        Endpoint first = new Endpoint();
        first.setPath("/webhooks/crm");
        Endpoint second = new Endpoint();
        second.setPath("/webhooks/crm");
        properties.getConsumer().getEndpoints().add(first);
        properties.getConsumer().getEndpoints().add(second);

        assertThat(validator.validate(properties))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("endpointPathsUnique"));
    }

    @Test
    void rejectsSubMillisecondHandlerTimeoutAcceptsOneMillisecondBoundaryAndHandlesNullEndpoints() {
        WebhookProperties subMs = new WebhookProperties();
        subMs.getConsumer().getHandler().setTimeout(Duration.ofNanos(999_999));
        assertThat(validator.validate(subMs))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("timeoutPositive"));

        WebhookProperties oneMs = new WebhookProperties();
        oneMs.getConsumer().getHandler().setTimeout(Duration.ofMillis(1));
        assertThat(validator.validate(oneMs)).isEmpty();

        WebhookProperties nullEndpoints = new WebhookProperties();
        nullEndpoints.getConsumer().setEndpoints(null);
        assertThat(nullEndpoints.getConsumer().isEndpointPathsUnique()).isTrue();
        assertThat(validator.validate(nullEndpoints))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).contains("endpoints"));
    }
}
