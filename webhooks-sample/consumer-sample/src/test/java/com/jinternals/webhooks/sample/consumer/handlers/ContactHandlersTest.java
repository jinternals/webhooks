package com.jinternals.webhooks.sample.consumer.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.sample.consumer.events.ContactCreated;
import com.jinternals.webhooks.sample.consumer.events.ContactDeleted;
import com.jinternals.webhooks.sample.consumer.events.ContactUpdated;
import com.jinternals.webhooks.sample.consumer.support.LogCapture;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactHandlersTest {

    private final ContactHandlers handlers = new ContactHandlers();

    private WebhookMetadata meta(String type, Object event, Instant eventTs) {
        return new WebhookMetadata(
                type,
                "msg_1",
                Instant.now().minusSeconds(1),
                eventTs,
                "/webhooks/crm",
                "127.0.0.1",
                event,
                Map.of(),
                Map.of());
    }

    @Test
    void onCreatedLogsRedactedEmail() {
        ContactCreated event = new ContactCreated();
        event.setId("c-1");
        event.setFullName("Ada Lovelace");
        event.setEmail("ada@example.com");

        try (LogCapture log = LogCapture.forClass(ContactHandlers.class)) {
            handlers.onCreated(event, meta("contact.created", event, Instant.now()));

            String line = log.lastFormattedMessage();
            assertThat(line).contains("c-1");
            assertThat(line).contains("***REDACTED***"); // email masked via redactedEvent()
            assertThat(line).doesNotContain("ada@example.com"); // real value never logged
        }
    }

    @Test
    void onUpdatedLogsIdAndEventLag() {
        // The webhook-id and trace id are carried by the MDC + log pattern (see TraceContextFilter and
        // SPEC §9b), not by the handler's message, so the handler line itself logs only id + eventLag.
        ContactUpdated event = new ContactUpdated();
        event.setId("c-2");

        try (LogCapture log = LogCapture.forClass(ContactHandlers.class)) {
            handlers.onUpdated(
                    event, meta("contact.updated", event, Instant.now().minusSeconds(2)));

            String line = log.lastFormattedMessage();
            assertThat(line).contains("c-2");
            assertThat(line).doesNotContain("eventLag=null"); // eventLag present (non-null)
        }
    }

    @Test
    void onUpdatedLogsNullEventLagWhenNoEventTimestamp() {
        ContactUpdated event = new ContactUpdated();
        event.setId("c-2");

        try (LogCapture log = LogCapture.forClass(ContactHandlers.class)) {
            handlers.onUpdated(event, meta("contact.updated", event, null));
            assertThat(log.lastFormattedMessage()).contains("eventLag=null");
        }
    }

    @Test
    void onDeletedLogsIdEndpointAndDeliveryLag() {
        ContactDeleted event = new ContactDeleted();
        event.setId("c-3");

        try (LogCapture log = LogCapture.forClass(ContactHandlers.class)) {
            handlers.onDeleted(meta("contact.deleted", event, Instant.now()), event);

            String line = log.lastFormattedMessage();
            assertThat(line).contains("c-3", "/webhooks/crm");
            assertThat(line).contains("deliveryLag="); // deliveryLag read and logged
        }
    }
}
