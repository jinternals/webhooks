package com.jinternals.webhooks.sample.producer.nats.events;

import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@WebhookType("contact.created")
public class ContactCreated {
    private String id;
    private String fullName;

    @Redact
    private String email;
}
