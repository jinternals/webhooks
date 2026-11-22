package com.jinternals.webhooks.sample.producer.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the {@link QueueingWebhookTransport} {@link WebhookTransport} SPI replacement example documented in
 * {@code docs/reference/beans-and-spis.md}.
 */
@SpringBootTest
@ActiveProfiles("queue-transport")
class QueueTransportSpiExampleTest {

    @Autowired
    private CrmWebhooks crmWebhooks;

    @Autowired
    private WebhookTransport webhookTransport;

    @Autowired
    private QueueingWebhookTransport queueingTransport;

    @Test
    void replacesDefaultTransportAndEnqueuesSignedEnvelope() {
        assertThat(webhookTransport).isSameAs(queueingTransport);
        queueingTransport.queue().clear();

        ContactCreated event = new ContactCreated();
        event.setId("c-queue-1");
        event.setFullName("Grace Hopper");
        event.setEmail("grace@example.com");

        crmWebhooks.contactCreated(event);

        assertThat(queueingTransport.queue()).hasSize(1);
        QueueingWebhookTransport.OutboundMessage queued =
                queueingTransport.queue().poll();
        assertThat(queued).isNotNull();
        assertThat(queued.url()).endsWith("/webhooks/crm");
        assertThat(queued.headers())
                .containsKeys(WebhookHeaders.ID, WebhookHeaders.TIMESTAMP, WebhookHeaders.SIGNATURE);
        assertThat(queued.body()).contains("\"contact.created\"", "\"c-queue-1\"");
    }
}
