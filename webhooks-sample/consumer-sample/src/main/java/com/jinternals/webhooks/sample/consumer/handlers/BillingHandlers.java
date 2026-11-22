package com.jinternals.webhooks.sample.consumer.handlers;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.sample.consumer.events.InvoicePaid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler for the <strong>single-type</strong> billing endpoint
 * ({@code /webhooks/billing/invoice-paid}), which accepts only {@code invoice.paid}.
 */
@Component
public class BillingHandlers {

    private static final Logger log = LoggerFactory.getLogger(BillingHandlers.class);

    @WebhookHandler
    public void onInvoicePaid(InvoicePaid event, WebhookMetadata metadata) {
        log.info(
                "invoice.paid invoiceId={} customerId={} amount={} {} webhookId={}",
                event.getInvoiceId(),
                event.getCustomerId(),
                event.getAmount(),
                event.getCurrency(),
                metadata.webhookId());
    }
}
