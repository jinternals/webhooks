package com.jinternals.webhooks.consumer.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a webhook handler (consumer-side, modelled on {@code @EventListener}).
 *
 * <p>The method must declare exactly one {@link WebhookType}-annotated event parameter, and may optionally
 * declare a {@code WebhookMetadata} parameter (in either order). Examples:
 *
 * <pre>
 *   &#64;WebhookHandler
 *   public void on(ContactCreated event) { ... }
 *
 *   &#64;WebhookHandler
 *   public void on(ContactUpdated event, WebhookMetadata metadata) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebhookHandler {}
