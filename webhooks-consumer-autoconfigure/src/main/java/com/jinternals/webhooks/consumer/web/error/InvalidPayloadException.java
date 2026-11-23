package com.jinternals.webhooks.consumer.web.error;

import java.io.Serial;

/**
 * Raised when a <strong>verified</strong> request body cannot be parsed as a Standard Webhooks envelope,
 * or when the envelope's {@code data} node cannot be strictly deserialized into its {@code @WebhookType}
 * class because it carries unknown JSON fields or malformed values (see SPEC.md §6a, §12).
 *
 * <p>This is a <em>sender</em> error — the signature was valid but the payload is not what the contract
 * requires — so {@link GlobalExceptionHandler} maps it to a {@code 400}. Without this, a Jackson parse
 * failure would fall through to the generic {@code 500} handler and the sender would retry a payload
 * that can never succeed.
 */
public class InvalidPayloadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String type;

    /** Envelope could not be parsed at all (no modelled type is known yet). */
    public InvalidPayloadException(String reason, Throwable cause) {
        super("Webhook payload could not be parsed: " + reason, cause);
        this.type = null;
    }

    /** The {@code data} node could not be deserialized into the {@code @WebhookType} class for {@code type}. */
    public InvalidPayloadException(String type, String reason, Throwable cause) {
        super("Webhook payload for type '" + type + "' could not be deserialized: " + reason, cause);
        this.type = type;
    }

    /** The event type whose payload was invalid, or {@code null} when the envelope itself was unparseable. */
    public String getType() {
        return type;
    }
}
