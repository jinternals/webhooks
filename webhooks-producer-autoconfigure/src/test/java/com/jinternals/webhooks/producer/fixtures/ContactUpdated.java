package com.jinternals.webhooks.producer.fixtures;

import com.jinternals.webhooks.core.annotations.WebhookType;

/** A second shared test event, for exercising clients that declare more than one webhook method. */
@WebhookType("contact.updated")
public class ContactUpdated {
    public String id = "c-2";
}
