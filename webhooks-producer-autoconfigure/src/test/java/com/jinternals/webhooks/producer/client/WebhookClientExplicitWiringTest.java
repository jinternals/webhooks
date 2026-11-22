package com.jinternals.webhooks.producer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;
import com.jinternals.webhooks.producer.fixtures.explicit.ExplicitCrmWebhooks;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Wiring test for the explicit {@code clients = {...}} selector — the zero-scan, refactor-safe path. The
 * interface is registered by class reference alone, with no classpath scanning.
 */
@SpringBootTest(
        properties = {
            "webhooks.producer.destinations.crm.url=https://crm.example.com/webhooks",
            "webhooks.producer.destinations.crm.secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
        })
class WebhookClientExplicitWiringTest {

    @SpringBootApplication(scanBasePackages = "com.jinternals.webhooks.producer.fixtures.none")
    @EnableWebhookClients(clients = ExplicitCrmWebhooks.class)
    static class TestApp {}

    @MockitoBean
    private WebhookTransport transport;

    @Autowired
    private ExplicitCrmWebhooks crmWebhooks;

    @Test
    void explicitClientIsWiredWithoutScanning() {
        assertThat(crmWebhooks).isNotNull();

        crmWebhooks.contactCreated(new ContactCreated());

        verify(transport).send(eq("https://crm.example.com/webhooks"), any(), anyString());
    }
}
