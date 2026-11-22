package com.jinternals.webhooks.producer.fixtures.invalidtype;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/**
 * A {@code @WebhookClient} with a blank destination. Type-level validation must reject it (a publish to a
 * blank destination resolves no url/secret), rather than register a client that fails later (SPEC.md §19).
 */
@WebhookClient(destination = "   ")
public interface BlankDestinationWebhooks {
    void contactCreated(ContactCreated event);
}
