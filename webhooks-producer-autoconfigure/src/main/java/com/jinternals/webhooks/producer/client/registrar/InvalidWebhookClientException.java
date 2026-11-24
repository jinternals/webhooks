package com.jinternals.webhooks.producer.client.registrar;

import java.io.Serial;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

/**
 * Thrown at startup when a {@code @WebhookClient} interface declares a method that does not match the
 * required shape: exactly one {@code @WebhookType}-annotated event parameter and a {@code void} return (see
 * SPEC.md §19). A publish is fire-and-forget — its outcome is signalled by a thrown exception, not a
 * return value — so there is nothing meaningful for a client method to return.
 *
 * <p>Thrown in two shapes: for a malformed <em>method</em> (carrying the offending {@link Method}), and
 * for a malformed client <em>type</em> — a {@code @WebhookClient} that is not an interface, is missing the
 * annotation, or has a blank destination (carrying only a message, with {@link #getMethod()} {@code null}).
 *
 * <p>Paired with {@code WebhookClientFailureAnalyzer}, which renders this as a clean, actionable Spring
 * Boot startup failure (description + action) instead of a raw stack trace.
 */
public class InvalidWebhookClientException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient @Nullable Method method;

    public InvalidWebhookClientException(Method method, String reason) {
        super("@WebhookClient method " + method.getDeclaringClass().getSimpleName() + "#" + method.getName() + " "
                + reason);
        this.method = method;
    }

    /**
     * A type-level failure (not tied to a single method): the {@code @WebhookClient} is not an interface, is
     * missing the annotation, or declares a blank destination. {@link #getMethod()} is {@code null} here.
     */
    public InvalidWebhookClientException(String message) {
        super(message);
        this.method = null;
    }

    /** The offending client interface method, or {@code null} for a type-level failure. */
    public @Nullable Method getMethod() {
        return method;
    }
}
