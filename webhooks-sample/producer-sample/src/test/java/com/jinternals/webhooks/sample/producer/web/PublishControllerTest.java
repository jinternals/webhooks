package com.jinternals.webhooks.sample.producer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooksV2a;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;
import com.jinternals.webhooks.sample.producer.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PublishControllerTest {

    private final CrmWebhooks crmWebhooks = mock(CrmWebhooks.class);
    private final CrmWebhooksV2a crmWebhooksV2a = mock(CrmWebhooksV2a.class);
    private final PublishController controller = new PublishController(crmWebhooks, crmWebhooksV2a);

    private ContactCreated event(String id) {
        ContactCreated event = new ContactCreated();
        event.setId(id);
        event.setFullName("Ada Lovelace");
        event.setEmail("ada@example.com");
        return event;
    }

    @Test
    void publishesViaTheV1ClientAndReturns202() {
        ContactCreated event = event("c-1");

        try (LogCapture logs = LogCapture.forClass(PublishController.class)) {
            ResponseEntity<String> response = controller.publishContactCreated(event);

            verify(crmWebhooks).contactCreated(event); // the v1 proxy client is actually invoked
            verifyNoInteractions(crmWebhooksV2a); // …and the v2a client is NOT
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo("published");
            assertThat(logs.lastFormattedMessage()).contains("published contact.created", "v1", "c-1");
        }
    }

    @Test
    void publishesViaTheV2aClientAndReturns202() {
        ContactCreated event = event("c-9");

        try (LogCapture logs = LogCapture.forClass(PublishController.class)) {
            ResponseEntity<String> response = controller.publishContactCreatedV2a(event);

            verify(crmWebhooksV2a).contactCreated(event); // the v2a proxy client is actually invoked
            verifyNoInteractions(crmWebhooks); // …and the v1 client is NOT
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo("published");
            assertThat(logs.lastFormattedMessage()).contains("published contact.created", "v2a", "c-9");
        }
    }

    @Test
    void mapsAV1DeliveryFailureTo502() {
        ContactCreated event = event("c-2");
        doThrow(new WebhookDeliveryException("connection refused"))
                .when(crmWebhooks)
                .contactCreated(event);

        try (LogCapture logs = LogCapture.forClass(PublishController.class)) {
            ResponseEntity<String> response = controller.publishContactCreated(event);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isEqualTo("delivery failed: connection refused");
            assertThat(logs.lastFormattedMessage())
                    .contains("delivery of contact.created", "v1", "c-2", "connection refused");
        }
    }

    @Test
    void mapsAV2aDeliveryFailureTo502() {
        ContactCreated event = event("c-3");
        doThrow(new WebhookDeliveryException("connection refused"))
                .when(crmWebhooksV2a)
                .contactCreated(event);

        try (LogCapture logs = LogCapture.forClass(PublishController.class)) {
            ResponseEntity<String> response = controller.publishContactCreatedV2a(event);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isEqualTo("delivery failed: connection refused");
            assertThat(logs.lastFormattedMessage())
                    .contains("delivery of contact.created", "v2a", "c-3", "connection refused");
        }
    }
}
