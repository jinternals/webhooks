package com.jinternals.webhooks.sample.consumer.events;

import com.jinternals.webhooks.core.annotations.WebhookType;
import lombok.Data;

/** Demo CRM event — maps the webhook {@code data} for {@code contact.updated}. */
@WebhookType("contact.updated")
@Data
public class ContactUpdated {

    private String id;
    private String fullName;
    private String email;
}
