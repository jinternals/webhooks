package com.jinternals.webhooks.sample.producer.client;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;

/**
 * Demo producer client for the <strong>asymmetric {@code v2a}</strong> path. Identical in shape to
 * {@link CrmWebhooks}, but targets the {@code demo-v2a} destination, which is configured with a
 * {@code whsk_} Ed25519 private key and an {@code endpoint-id} (see
 * {@code webhooks.producer.destinations.demo-v2a}). The toolkit selects the signing scheme purely from the
 * key prefix: {@code whsk_} ⇒ {@code v2a}, so this client signs every delivery with Ed25519 over
 * {@code endpoint_id.msg_id.timestamp.payload} (SPEC.md §19i) — nothing else in the code differs.
 *
 * <p>Registered explicitly alongside {@link CrmWebhooks} via
 * {@code @EnableWebhookClients(clients = ...)} on {@code ProducerSampleApplication}.
 */
@WebhookClient(destination = "demo-v2a")
public interface CrmWebhooksV2a {

    /** Publish a {@code contact.created} webhook signed with the asymmetric {@code v2a} scheme. */
    void contactCreated(ContactCreated event);
}
