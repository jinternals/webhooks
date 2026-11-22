package com.jinternals.webhooks.producer.fixtures.invalidtype;

import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A concrete class carrying {@code @WebhookClient}. A {@code @WebhookClient} must be an interface, so
 * type-level validation must reject this when it is handed to the registrar directly (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public class NotAnInterfaceWebhooks {}
