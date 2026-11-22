package com.jinternals.webhooks.producer.fixtures.inherited;

import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/**
 * A super-interface declaring an abstract webhook method but carrying no {@code @WebhookClient} annotation.
 * Extended by {@link InheritedWebhooks} so the registrar must validate the inherited abstract method via
 * {@code getMethods()}, not only the methods declared on the annotated sub-interface (SPEC.md §19).
 */
public interface BaseWebhooks {
    void contactCreated(ContactCreated event);
}
