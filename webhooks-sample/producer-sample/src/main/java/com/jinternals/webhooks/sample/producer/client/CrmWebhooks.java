package com.jinternals.webhooks.sample.producer.client;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;

/**
 * Demo producer client. The {@code webhooks-producer} library generates a proxy bean for this
 * interface; calling {@link #contactCreated(ContactCreated)} builds the Standard Webhooks envelope from
 * the event's {@code @WebhookType} ({@code contact.created}), signs it, and POSTs it to the {@code demo}
 * destination configured under {@code webhooks.producer.destinations.demo}.
 *
 * <p>Registered explicitly via {@code @EnableWebhookClients(clients = CrmWebhooks.class)} on
 * {@code ProducerSampleApplication} — the zero-scan, refactor-safe path (see SPEC.md §19).
 */
@WebhookClient(destination = "demo")
public interface CrmWebhooks {

    /** Publish a {@code contact.created} webhook. The method name is free; only the parameter type matters. */
    void contactCreated(ContactCreated event);
}
