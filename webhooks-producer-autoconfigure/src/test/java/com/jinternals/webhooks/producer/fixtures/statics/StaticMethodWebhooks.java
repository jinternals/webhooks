package com.jinternals.webhooks.producer.fixtures.statics;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A {@code @WebhookClient} with a {@code static} helper method whose signature (non-void return, no
 * {@code @WebhookType} argument) would fail validation if it were treated as a webhook method. A static
 * interface method has a body and is never a publish method, so the registrar must skip it and validate
 * only the abstract publish method, letting this interface register cleanly (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface StaticMethodWebhooks {

    @WebhookType("contact.created")
    class ContactCreated {}

    void contactCreated(ContactCreated event);

    static String describe() {
        return "crm client";
    }
}
