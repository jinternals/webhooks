package com.jinternals.webhooks.producer.fixtures.multi;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/**
 * A {@code @WebhookClient} whose FIRST webhook method is well-formed and whose SECOND is invalid (no
 * {@code @WebhookType} argument). Proves the registrar validates <em>every</em> distinct method, not just
 * the first: a signature-identity bug that collapsed the two methods to one key would let the first (valid)
 * method mask the second (invalid) one and skip its rejection (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface ValidThenInvalidWebhooks {
    void contactCreated(ContactCreated event);

    void broken(String notAnEvent);
}
