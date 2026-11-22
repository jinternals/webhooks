package com.jinternals.webhooks.sample.consumer.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.sample.consumer.events.InvoicePaid;
import com.jinternals.webhooks.sample.consumer.support.LogCapture;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BillingHandlersTest {

    private final BillingHandlers handlers = new BillingHandlers();

    private InvoicePaid invoice() {
        InvoicePaid e = new InvoicePaid();
        e.setInvoiceId("inv_1");
        e.setCustomerId("cus_9");
        e.setAmount(new BigDecimal("42.50"));
        e.setCurrency("GBP");
        return e;
    }

    private WebhookMetadata meta() {
        return new WebhookMetadata(
                "invoice.paid",
                "msg_7",
                Instant.now(),
                Instant.now(),
                "/webhooks/billing/invoice-paid",
                "127.0.0.1",
                invoice(),
                Map.of(),
                Map.of());
    }

    @Test
    void logsAllInvoiceFieldsAndWebhookId() {
        try (LogCapture log = LogCapture.forClass(BillingHandlers.class)) {
            handlers.onInvoicePaid(invoice(), meta());

            String line = log.lastFormattedMessage();
            assertThat(log.count()).isEqualTo(1);
            assertThat(line).contains("inv_1", "cus_9", "42.50", "GBP", "msg_7");
        }
    }
}
