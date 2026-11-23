package com.jinternals.webhooks.consumer.web.error;

import java.io.Serial;

/**
 * Raised when a verified, accepted, and modelled event has no registered {@code @WebhookHandler} to run
 * (see SPEC.md §10, §15). The event type is known to the registry and allowed by the endpoint, yet no
 * bean declares a handler method for it — a configuration gap, not a sender error.
 *
 * <p>Previously the dispatcher logged and silently returned {@code 204}, so the sender believed the
 * delivery was processed and stopped retrying while nothing had actually handled it. Surfacing this as an
 * exception lets {@link GlobalExceptionHandler} return a {@code 500} so the sender retries and the gap is
 * visible, rather than events being dropped on the floor.
 */
public class NoWebhookHandlerException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String type;

    public NoWebhookHandlerException(String type, String eventClassName) {
        super("No @WebhookHandler registered for event type '" + type + "' (" + eventClassName + ")");
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
