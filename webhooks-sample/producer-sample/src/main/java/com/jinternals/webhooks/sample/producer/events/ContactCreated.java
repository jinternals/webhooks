package com.jinternals.webhooks.sample.producer.events;

import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import lombok.Data;

/**
 * Demo CRM event — maps the webhook {@code data} for {@code contact.created}. On the producer side the
 * {@code @WebhookType} selects the envelope type; the receiving consumer enforces the field contract with Bean
 * Validation (see the consumer sample's copy of this event, SPEC §6a).
 */
@WebhookType("contact.created")
@Data
public class ContactCreated {

    private String id;

    private String fullName;

    /** Sensitive: masked as {@code ***REDACTED***} when logged via the redaction-aware renderer. */
    @Redact
    private String email;
}
