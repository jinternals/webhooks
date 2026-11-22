package com.jinternals.webhooks.producer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.fixtures.ContactCreated;
import com.jinternals.webhooks.producer.fixtures.scan.ScanCrmWebhooks;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end wiring test for the {@code basePackages} selector: {@code @EnableWebhookClients} must scan
 * the package, discover the {@code @WebhookClient} interface, register a proxy bean, and route its calls
 * through the real {@link WebhookPublisher} down to the (mocked) {@link WebhookTransport}.
 */
@SpringBootTest(
        properties = {
            "webhooks.producer.destinations.crm.url=https://crm.example.com/webhooks",
            "webhooks.producer.destinations.crm.secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
        })
class WebhookClientWiringTest {

    @SpringBootApplication(scanBasePackages = "com.jinternals.webhooks.producer.fixtures.none")
    @EnableWebhookClients(basePackages = "com.jinternals.webhooks.producer.fixtures.scan")
    static class TestApp {}

    @MockitoBean
    private WebhookTransport transport;

    @Autowired
    private ScanCrmWebhooks crmWebhooks;

    @Test
    void proxyIsWiredAndPublishesThroughTransport() {
        assertThat(crmWebhooks).isNotNull();

        crmWebhooks.contactCreated(new ContactCreated());

        verify(transport).send(eq("https://crm.example.com/webhooks"), any(), anyString());
    }
}
