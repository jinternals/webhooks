package com.jinternals.webhooks.producer.fixtures.inherited;

import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A {@code @WebhookClient} whose only webhook method is <em>inherited</em> (abstract) from
 * {@link BaseWebhooks}. The registrar must still validate it — it iterates {@code getMethods()}, not just
 * {@code getDeclaredMethods()} — so this interface registers cleanly (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface InheritedWebhooks extends BaseWebhooks {}
