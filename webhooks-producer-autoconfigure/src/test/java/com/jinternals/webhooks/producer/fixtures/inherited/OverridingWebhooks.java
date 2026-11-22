package com.jinternals.webhooks.producer.fixtures.inherited;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/**
 * A {@code @WebhookClient} that re-declares (overrides) a webhook method it also inherits from
 * {@link BaseWebhooks}. {@code getMethods()} can surface the same method twice, so the registrar must
 * deduplicate by signature and validate it only once (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface OverridingWebhooks extends BaseWebhooks {
    @Override
    void contactCreated(ContactCreated event);
}
