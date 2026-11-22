package com.jinternals.webhooks.producer.fixtures.multi;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;
import com.jinternals.webhooks.producer.fixtures.ContactUpdated;

/**
 * A {@code @WebhookClient} declaring two <em>distinct</em> well-formed webhook methods. Used to prove the
 * registrar validates every distinct method (not just the first) and that method identity is per-signature
 * — so two different methods are two different dedup keys (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface TwoMethodWebhooks {
    void contactCreated(ContactCreated event);

    void contactUpdated(ContactUpdated event);
}
