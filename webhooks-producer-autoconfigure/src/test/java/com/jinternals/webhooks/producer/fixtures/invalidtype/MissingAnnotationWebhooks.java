package com.jinternals.webhooks.producer.fixtures.invalidtype;

import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/**
 * An interface with no {@code @WebhookClient} annotation. Type-level validation must reject it so a
 * {@link com.jinternals.webhooks.producer.client.registrar.WebhookClientFactoryBean} is never built for a
 * type whose missing annotation would otherwise NPE at first use (SPEC.md §19).
 */
public interface MissingAnnotationWebhooks {
    void contactCreated(ContactCreated event);
}
