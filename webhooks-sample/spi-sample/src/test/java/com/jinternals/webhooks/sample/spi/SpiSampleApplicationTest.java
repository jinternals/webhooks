package com.jinternals.webhooks.sample.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpiSampleApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebhookProducerProperties producerProperties;

    @Autowired
    private SpiSampleApplication.CrmWebhooks crmWebhooks;

    @Autowired
    private SpiSampleApplication.ContactHandlers contactHandlers;

    @Autowired
    private IdempotencyGuard idempotencyGuard;

    @Autowired
    private DestinationUrlValidator urlValidator;

    @Autowired
    private SpiSampleApplication.RecordedHooks recordedHooks;

    @BeforeEach
    void pointProducerAtRandomLocalPort() {
        producerProperties.getDestinations().get("crm").setUrl("http://127.0.0.1:" + port + "/webhooks/crm");
        contactHandlers.clear();
    }

    @Test
    void deliversEndToEndInSingleAppUsingCustomSecretProvidersAndSharedIdempotency() {
        assertThat(idempotencyGuard).isInstanceOf(SharedStoreIdempotencyGuard.class);
        assertThat(urlValidator).isInstanceOf(PrivateIpBlockingUrlValidator.class);

        crmWebhooks.contactCreated(
                new SpiSampleApplication.ContactCreated("c-spi-1", "Ada Lovelace", "ada@example.com"));

        assertThat(contactHandlers.received()).hasSize(1);
        assertThat(contactHandlers.received().get(0).redactedEvent()).contains("***REDACTED***");
    }

    @Test
    void customUrlValidatorBlocksCloudMetadataPrivateIpv6UlaWildcardAndNonHttpSchemes() {
        assertThatThrownBy(() -> urlValidator.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Blocked private/link-local destination IP");

        assertThatThrownBy(() -> urlValidator.validate("http://10.0.0.5/internal"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Blocked private/link-local destination IP");

        assertThatThrownBy(() -> urlValidator.validate("http://[fd00::1]:8080/internal"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Blocked private/link-local destination IP");

        assertThatThrownBy(() -> urlValidator.validate("http://0.0.0.0:8080/internal"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Blocked private/link-local destination IP");

        assertThatThrownBy(() -> urlValidator.validate("file:///etc/passwd"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("http/https host required");

        assertThatThrownBy(() -> urlValidator.validate("not-a-url"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("http/https host required");

        PrivateIpBlockingUrlValidator strictValidator = new PrivateIpBlockingUrlValidator(false);
        assertThatThrownBy(() -> strictValidator.validate("http://127.0.0.1:8080/hook"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("Blocked loopback destination IP");
    }

    @Test
    void sharedStoreIdempotencyGuardSupportsReserveReleaseAndCommit() {
        assertThat(idempotencyGuard.tryBegin("msg_spi_99")).isTrue();
        assertThat(idempotencyGuard.tryBegin("msg_spi_99")).isFalse();
        idempotencyGuard.release("msg_spi_99");
        assertThat(idempotencyGuard.tryBegin("msg_spi_99")).isTrue();
        idempotencyGuard.markProcessed("msg_spi_99");
        assertThat(idempotencyGuard.tryBegin("msg_spi_99")).isFalse();

        // Also exercise RecordedHooks HandlerFailureListener callbacks
        recordedHooks.onHandlerFailed("contact.created", new RuntimeException("boom"));
        recordedHooks.onHandlerTimeout("contact.created");
        assertThat(recordedHooks.asyncHandlerFailures()).contains("failed:contact.created", "timeout:contact.created");
        assertThat(ReflectionTestUtils.getField(urlValidator, "allowLoopback")).isEqualTo(true);
    }
}
