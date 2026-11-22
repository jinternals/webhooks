package com.jinternals.webhooks.sample.consumer.events;

import com.jinternals.webhooks.core.annotations.WebhookType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

/**
 * Demo billing event — maps the webhook {@code data} for {@code invoice.paid}.
 *
 * <p>The {@code @WebhookType} class <em>is</em> the schema: the field constraints below are enforced by Bean
 * Validation after deserialization (SPEC §6a).
 */
@WebhookType("invoice.paid")
@Data
public class InvoicePaid {

    @NotBlank
    private String invoiceId;

    @NotBlank
    private String customerId;

    @Positive
    private BigDecimal amount;

    @Size(min = 3, max = 3)
    private String currency;
}
