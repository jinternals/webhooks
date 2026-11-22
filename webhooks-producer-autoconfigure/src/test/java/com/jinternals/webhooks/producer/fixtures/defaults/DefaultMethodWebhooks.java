package com.jinternals.webhooks.producer.fixtures.defaults;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A {@code @WebhookClient} with a {@code default} helper method whose signature (non-void return, no
 * {@code @WebhookType} argument) would fail validation if it were treated as a webhook method. The registrar
 * must skip {@code default}/synthetic methods and validate only the abstract publish method, so this
 * interface registers cleanly (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface DefaultMethodWebhooks {

    @WebhookType("contact.created")
    class ContactCreated {}

    void contactCreated(ContactCreated event);

    default String describe() {
        return "crm client";
    }
}
