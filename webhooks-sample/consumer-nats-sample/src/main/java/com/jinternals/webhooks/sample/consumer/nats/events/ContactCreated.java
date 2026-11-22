package com.jinternals.webhooks.sample.consumer.nats.events;

import com.jinternals.webhooks.core.annotations.Redact;
import com.jinternals.webhooks.core.annotations.WebhookType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@WebhookType("contact.created")
public class ContactCreated {
    @NotBlank
    private String id;

    @NotBlank
    private String fullName;

    @Email
    @Redact
    private String email;
}
