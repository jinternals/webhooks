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
 * Wiring test for the type-safe {@code basePackageClasses} selector: the package of the anchor class is
 * scanned for {@code @WebhookClient} interfaces, with no magic package string.
 */
@SpringBootTest(
        properties = {
            "webhooks.producer.destinations.crm.url=https://crm.example.com/webhooks",
            "webhooks.producer.destinations.crm.secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
        })
class WebhookClientAnchorWiringTest {

    @SpringBootApplication(scanBasePackages = "com.jinternals.webhooks.producer.fixtures.none")
    @EnableWebhookClients(basePackageClasses = ScanCrmWebhooks.class)
    static class TestApp {}

    @MockitoBean
    private WebhookTransport transport;

    @Autowired
    private ScanCrmWebhooks crmWebhooks;

    @Test
    void anchorPackageIsScannedAndWired() {
        assertThat(crmWebhooks).isNotNull();

        crmWebhooks.contactCreated(new ContactCreated());

        verify(transport).send(eq("https://crm.example.com/webhooks"), any(), anyString());
    }
}
