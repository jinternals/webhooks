package com.jinternals.webhooks.producer.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an <strong>interface</strong> as an annotation-driven Standard Webhooks client (see SPEC.md
 * §19). The producer generates a proxy bean for the interface; each method takes a single
 * {@code @WebhookType}-annotated event argument, and invoking it builds the envelope, signs it, and POSTs it
 * to the configured {@link #destination()} via the {@code WebhookTransport}.
 *
 * <pre>
 * &#64;WebhookClient(destination = "crm")
 * public interface CrmWebhooks {
 *     void contactCreated(ContactCreated event);   // type derived from &#64;WebhookType on ContactCreated
 *     void contactDeleted(ContactDeleted event);
 * }
 * </pre>
 *
 * <p>The event's {@code type} string comes from its {@link com.jinternals.webhooks.core.annotations.WebhookType}
 * annotation, so the method name is free — only the parameter type matters. The destination's URL and
 * secret are resolved from {@code webhooks.producer.destinations.<destination>}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebhookClient {

    /**
     * The named destination this client publishes to, resolved from
     * {@code webhooks.producer.destinations.<name>} (url + secret). Must be non-blank.
     */
    String destination();
}
