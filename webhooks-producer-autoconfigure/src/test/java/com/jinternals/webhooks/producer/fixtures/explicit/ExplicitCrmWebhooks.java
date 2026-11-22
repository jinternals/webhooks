package com.jinternals.webhooks.producer.fixtures.explicit;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/** A top-level {@code @WebhookClient} interface registered explicitly via {@code clients = {...}}. */
@WebhookClient(destination = "crm")
public interface ExplicitCrmWebhooks {
    void contactCreated(ContactCreated event);
}
