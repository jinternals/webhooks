package com.jinternals.webhooks.sample.spi.nullsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.web.receive.WebhookRequestHeaders;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.sample.spi.SharedStoreIdempotencyGuard;
import com.jinternals.webhooks.sample.spi.SpiSampleApplication;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JSpecify {@code @NullMarked} / {@code @Nullable} contracts of the published webhook libraries
 * under both compile-time NullAway ({@code JSpecifyMode=true}) and runtime execution.
 */
class JSpecifyNullSafetyContractTest {

    @Test
    void exercisesConsumerCoreAndProducerNullabilityContracts() {
        Map<String, @Nullable String> headers = new LinkedHashMap<>();
        headers.put("x-tenant-id", "acme");
        headers.put("x-empty", null);

        Map<String, @Nullable Object> attributes = new LinkedHashMap<>();
        attributes.put("nullableAttr", null);

        SpiSampleApplication.ContactCreated event =
                new SpiSampleApplication.ContactCreated("c-null-1", "Grace Hopper", "grace@example.com");
        WebhookMetadata metadata = new WebhookMetadata(
                "contact.created", null, null, null, "/webhooks/crm", null, event, headers, attributes);

        String summary = NullSafeWebhookInspector.summarizeMetadata(metadata);
        assertThat(summary)
                .contains("contact.created|/webhooks/crm|<unsigned>|<unknown-ip>|acme|")
                .contains("***REDACTED***")
                .doesNotContain("grace@example.com");

        Envelope envelope = new Envelope();
        envelope.setType(null);
        envelope.setTimestamp(null);
        envelope.setData(null);
        WebhookTypeRegistry registry =
                new WebhookTypeRegistry(Map.of("contact.created", SpiSampleApplication.ContactCreated.class));
        assertThat(NullSafeWebhookInspector.resolveEnvelopeTarget(envelope, registry))
                .isNull();

        envelope.setType("contact.created");
        assertThat(NullSafeWebhookInspector.resolveEnvelopeTarget(envelope, registry))
                .isEqualTo(SpiSampleApplication.ContactCreated.class);

        @Nullable String missingHeader = WebhookRequestHeaders.first(Map.of("webhook-id", List.of()), "webhook-id");
        assertThat(missingHeader).isNull();

        assertThat(SafeUrl.sanitize(null)).isEqualTo("<null>");
        assertThat(SafeUrl.redactMessage(null, "https://user:secret@example.com/hook"))
                .isNull();

        DeliveryFailure failure = new DeliveryFailure(
                "https://user:secret@example.com/hook?token=abc",
                Map.of("webhook-id", List.of("msg_1")),
                "{}",
                3,
                DeliveryFailure.Reason.EXHAUSTED,
                new WebhookDeliveryException(
                        "I/O error on POST request for \"https://user:secret@example.com/hook?token=abc\""));
        assertThat(NullSafeWebhookInspector.describeDeadLetter(failure))
                .isEqualTo(
                        "EXHAUSTED:https://example.com/hook:I/O error on POST request for \"https://example.com/hook\"");

        SharedStoreIdempotencyGuard guard =
                new SharedStoreIdempotencyGuard(new SharedStoreIdempotencyGuard.InMemoryKeyValueStore());
        assertThat(NullSafeWebhookInspector.redactNullablePayload(null, guard, null))
                .isEqualTo("null");
        assertThat(NullSafeWebhookInspector.redactNullablePayload(event, guard, "msg_null_1"))
                .contains("***REDACTED***");
    }
}
