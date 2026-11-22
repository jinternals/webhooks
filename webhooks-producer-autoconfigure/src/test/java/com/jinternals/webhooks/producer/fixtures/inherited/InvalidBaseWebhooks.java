package com.jinternals.webhooks.producer.fixtures.inherited;

/**
 * A super-interface declaring an abstract method with an invalid webhook signature (missing the
 * {@code @WebhookType} argument). Extended by {@link InvalidInheritedWebhooks} to prove the registrar
 * validates inherited abstract methods and rejects a malformed one (SPEC.md §19).
 */
public interface InvalidBaseWebhooks {
    void contactCreated(String notAnEvent);
}
