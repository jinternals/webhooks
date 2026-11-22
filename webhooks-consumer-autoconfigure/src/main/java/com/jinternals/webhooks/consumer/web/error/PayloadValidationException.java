package com.jinternals.webhooks.consumer.web.error;

import java.util.List;

/** Raised when an event payload fails Bean Validation against its {@code @WebhookType} class (see SPEC.md §6a, §12). */
public class PayloadValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String type;
    private final List<String> errors;

    public PayloadValidationException(String type, List<String> errors) {
        super("Payload for type '" + type + "' failed validation: " + errors);
        this.type = type;
        this.errors = List.copyOf(errors);
    }

    public String getType() {
        return type;
    }

    public List<String> getErrors() {
        return errors;
    }
}
