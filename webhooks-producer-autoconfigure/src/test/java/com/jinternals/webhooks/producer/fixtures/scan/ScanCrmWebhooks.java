package com.jinternals.webhooks.producer.fixtures.scan;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/** A top-level {@code @WebhookClient} interface in its own package, for the scanning-based wiring tests. */
@WebhookClient(destination = "crm")
public interface ScanCrmWebhooks {
    void contactCreated(ContactCreated event);
}
