package com.jinternals.webhooks.consumer.validation;

import com.jinternals.webhooks.consumer.web.error.PayloadValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-type payload validation using Jakarta Bean Validation (see SPEC.md §6a). The event
 * {@code @WebhookType} class <em>is</em> the schema: constraints are declared with annotations on its
 * fields ({@code @NotBlank}, {@code @Positive}, {@code @Size}, …) and enforced here on the already-
 * deserialized object.
 *
 * <p>Runs <strong>after</strong> signature verification and deserialization, and <strong>before</strong>
 * dispatch. Every deserialized event is validated; a type whose class declares no constraints trivially
 * passes. A violation yields a {@link PayloadValidationException} (→ {@code 400}).
 */
public class PayloadValidator {

    private final Validator validator;

    public PayloadValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * Validate the deserialized event against the Bean Validation constraints declared on its class.
     *
     * @throws PayloadValidationException if any constraint is violated
     */
    public void validate(String type, Object event) {
        Set<ConstraintViolation<Object>> violations = validator.validate(event);
        if (violations.isEmpty()) {
            return;
        }
        List<String> errors = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.toList());
        throw new PayloadValidationException(type, errors);
    }
}
