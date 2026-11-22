package com.jinternals.webhooks.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field on a {@link WebhookType}-annotated event as sensitive, so it is masked when the event is
 * rendered for logging (see SPEC.md §9a).
 *
 * <p>The field's real value is never written to logs via the framework's redaction-aware renderer
 * ({@code WebhookRedactor} / {@code WebhookMetadata.redactedEvent()}); it is shown as
 * {@code ***REDACTED***} (or a partially-masked value when {@link #show()} &gt; 0).
 *
 * <pre>
 * &#64;WebhookType("contact.created")
 * public class ContactCreated {
 *     private String id;
 *     &#64;Redact private String email;              // -&gt; ***REDACTED***
 *     &#64;Redact(show = 4) private String cardNumber; // -&gt; ************1234
 * }
 * </pre>
 *
 * <p>Redaction is a <em>logging</em> concern only — the real value is still present on the event
 * object and available to your handler logic. It is your responsibility to log through the
 * redaction-aware renderer rather than calling {@code event.getEmail()} and logging it directly.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Redact {

    /**
     * Number of trailing characters to leave visible (the rest masked with {@code *}). Default
     * {@code 0} fully replaces the value with {@code ***REDACTED***}. Useful for card tails, etc.
     */
    int show() default 0;
}
