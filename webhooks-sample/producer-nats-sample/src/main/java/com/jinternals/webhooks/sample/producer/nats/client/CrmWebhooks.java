package com.jinternals.webhooks.sample.producer.nats.client;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.sample.producer.nats.events.ContactCreated;

@WebhookClient(destination = "crm")
public interface CrmWebhooks {
    void contactCreated(ContactCreated event);
}
