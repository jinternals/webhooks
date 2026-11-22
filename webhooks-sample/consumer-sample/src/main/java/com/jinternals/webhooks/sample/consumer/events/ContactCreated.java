package com.jinternals.webhooks.sample.consumer.events;

import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Demo CRM event — maps the webhook {@code data} for {@code contact.created}.
 *
 * <p>The {@code @WebhookType} class <em>is</em> the schema: the field constraints below are enforced by Bean
 * Validation after deserialization (SPEC §6a).
 */
@WebhookType("contact.created")
@Data
public class ContactCreated {

    @NotBlank
    private String id;

    private String fullName;

    /** Sensitive: masked as {@code ***REDACTED***} when logged via the redaction-aware renderer. */
    @Redact
    @NotBlank
    @Email
    private String email;
}
