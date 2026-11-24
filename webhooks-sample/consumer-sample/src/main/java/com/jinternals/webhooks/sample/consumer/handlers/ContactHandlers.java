package com.jinternals.webhooks.sample.consumer.handlers;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.sample.consumer.events.ContactCreated;
import com.jinternals.webhooks.sample.consumer.events.ContactDeleted;
import com.jinternals.webhooks.sample.consumer.events.ContactUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handlers for the <strong>multi-type</strong> CRM endpoint ({@code /webhooks/crm}). One URL feeds
 * three event types, each routed to its own handler. Demonstrates both parameter orders for
 * {@code (Event, WebhookMetadata)} and {@code (WebhookMetadata, Event)} (a single-argument {@code (Event)}
 * signature is also supported when metadata is not needed).
 */
@Component
public class ContactHandlers {

    private static final Logger log = LoggerFactory.getLogger(ContactHandlers.class);

    /**
     * Signature: (event, metadata). Logs the payload through {@link WebhookMetadata#redactedEvent()}
     * so the {@code @Redact}-annotated {@code email} shows as {@code ***REDACTED***} — the real value
     * is still available to handler logic via {@code event.getEmail()} (see SPEC.md §9a).
     */
    @WebhookHandler
    public void onCreated(ContactCreated event, WebhookMetadata metadata) {
        if (log.isInfoEnabled()) {
            log.info("contact.created id={} event={}", event.getId(), metadata.redactedEvent());
        }
    }

    /**
     * Signature: (event, metadata). The {@code webhook-id} and trace id are <strong>not</strong> logged
     * explicitly here — the app's {@code TraceContextFilter} binds both to the MDC, and the log pattern
     * (`%X{webhook.id} %X{traceId}`) renders them on every line, including this one (see SPEC.md §9b). The
     * stable {@code webhook-id} also remains available to handler logic via {@code metadata.webhookId()}.
     */
    @WebhookHandler
    public void onUpdated(ContactUpdated event, WebhookMetadata metadata) {
        log.info(
                "contact.updated id={} eventLag={}",
                event.getId(),
                metadata.eventLag().orElse(null));
    }

    /** Signature: (metadata, event) — order-independent. */
    @WebhookHandler
    public void onDeleted(WebhookMetadata metadata, ContactDeleted event) {
        log.info(
                "contact.deleted id={} via endpoint={} deliveryLag={}",
                event.getId(),
                metadata.endpointPath(),
                metadata.deliveryLag().orElse(null));
    }
}
