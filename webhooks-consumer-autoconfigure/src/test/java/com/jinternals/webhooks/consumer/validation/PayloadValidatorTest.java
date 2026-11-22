package com.jinternals.webhooks.consumer.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.web.error.PayloadValidationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PayloadValidatorTest {

    private static ValidatorFactory factory;
    private static PayloadValidator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator beanValidator = factory.getValidator();
        validator = new PayloadValidator(beanValidator);
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    static class Sample {
        @NotBlank
        String id;

        @Positive
        int amount;

        Sample(String id, int amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    static class NoConstraints {
        String anything;
    }

    @Test
    void passesWhenAllConstraintsSatisfied() {
        assertThatCode(() -> validator.validate("sample", new Sample("id-1", 10)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWithSortedErrorsWhenConstraintsViolated() {
        assertThatThrownBy(() -> validator.validate("sample", new Sample(" ", -1)))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> {
                    PayloadValidationException pve = (PayloadValidationException) ex;
                    assertThat(pve.getType()).isEqualTo("sample");
                    assertThat(pve.getErrors()).hasSize(2);
                    // Errors carry the property path and are sorted deterministically.
                    assertThat(pve.getErrors().get(0)).startsWith("amount ");
                    assertThat(pve.getErrors().get(1)).startsWith("id ");
                });
    }

    @Test
    void typeWithoutConstraintsTriviallyPasses() {
        assertThatCode(() -> validator.validate("plain", new NoConstraints())).doesNotThrowAnyException();
    }
}
