package com.jinternals.webhooks.sample.consumer.events;

import com.jinternals.webhooks.core.annotations.WebhookType;
import lombok.Data;

/** Demo CRM event — maps the webhook {@code data} for {@code contact.deleted}. */
@WebhookType("contact.deleted")
@Data
public class ContactDeleted {

    private String id;
}
