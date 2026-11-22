package com.jinternals.webhooks.producer.fixtures;

import com.jinternals.webhooks.core.annotations.WebhookType;

/** Shared test event for the producer wiring tests. */
@WebhookType("contact.created")
public class ContactCreated {
    public String id = "c-1";
}
