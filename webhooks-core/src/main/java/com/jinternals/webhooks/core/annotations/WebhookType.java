package com.jinternals.webhooks.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps an event class to a Standard Webhooks {@code type} string.
 *
 * <p>Modelled on {@code jinternals-event-bus}'s {@code @EventMapper}. The value is the
 * full-stop-delimited event type, e.g. {@code "contact.created"}. On the consumer side the
 * {@code WebhookTypeRegistry} scans for these classes and builds a {@code type -> Class} lookup, so an
 * incoming webhook body can be deserialized by its {@code type} without a switch; on the producer
 * side the same annotation derives the {@code type} string written into the outgoing envelope.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebhookType {

    /** The Standard Webhooks event type, e.g. "contact.created". */
    String value();
}
