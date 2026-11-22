package com.jinternals.webhooks.producer.fixtures.mixed;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/** A valid {@code @WebhookClient} interface, scanned alongside a non-interface candidate in this package. */
@WebhookClient(destination = "crm")
public interface MixedScanWebhooks {
    void contactCreated(ContactCreated event);
}
