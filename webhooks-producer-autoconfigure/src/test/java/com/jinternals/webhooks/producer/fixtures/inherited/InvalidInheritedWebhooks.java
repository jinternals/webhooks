package com.jinternals.webhooks.producer.fixtures.inherited;

import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A {@code @WebhookClient} whose inherited abstract method (from {@link InvalidBaseWebhooks}) has an invalid
 * signature; validation must reject it eagerly because inherited methods are validated too (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface InvalidInheritedWebhooks extends InvalidBaseWebhooks {}
