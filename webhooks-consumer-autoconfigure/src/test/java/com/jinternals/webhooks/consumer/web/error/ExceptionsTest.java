package com.jinternals.webhooks.consumer.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionsTest {

    @Test
    void unknownTypeCarriesTypeAndPath() {
        UnknownTypeException ex = new UnknownTypeException("invoice.paid", "/webhooks/crm", "not allow-listed");
        assertThat(ex.getType()).isEqualTo("invoice.paid");
        assertThat(ex.getPath()).isEqualTo("/webhooks/crm");
        assertThat(ex.getMessage())
                .contains("invoice.paid")
                .contains("/webhooks/crm")
                .contains("not allow-listed");
    }

    @Test
    void payloadValidationCarriesTypeAndErrors() {
        PayloadValidationException ex = new PayloadValidationException("contact.created", List.of("e1", "e2"));
        assertThat(ex.getType()).isEqualTo("contact.created");
        assertThat(ex.getErrors()).containsExactly("e1", "e2");
        assertThat(ex.getMessage()).contains("contact.created");
    }

    @Test
    void payloadTooLargeCarriesLimit() {
        PayloadTooLargeException ex = new PayloadTooLargeException(256);
        assertThat(ex.getLimitBytes()).isEqualTo(256L);
        assertThat(ex.getMessage()).contains("256");
    }

    @Test
    void endpointNotConfiguredCarriesPath() {
        EndpointNotConfiguredException ex = new EndpointNotConfiguredException("/webhooks/crm");
        assertThat(ex.getMessage()).contains("/webhooks/crm");
    }

    @Test
    void invalidPayloadEnvelopeConstructorHasNullTypeAndCause() {
        RuntimeException cause = new RuntimeException("boom");
        InvalidPayloadException ex = new InvalidPayloadException("not json", cause);
        assertThat(ex.getType()).isNull();
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("could not be parsed").contains("not json");
    }

    @Test
    void invalidPayloadDeserializeConstructorCarriesTypeAndCause() {
        RuntimeException cause = new RuntimeException("bad field");
        InvalidPayloadException ex = new InvalidPayloadException("contact.created", "unknown field", cause);
        assertThat(ex.getType()).isEqualTo("contact.created");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
                .contains("contact.created")
                .contains("could not be deserialized")
                .contains("unknown field");
    }

    @Test
    void noWebhookHandlerCarriesType() {
        NoWebhookHandlerException ex = new NoWebhookHandlerException("contact.created", "com.example.ContactCreated");
        assertThat(ex.getType()).isEqualTo("contact.created");
        assertThat(ex.getMessage()).contains("contact.created").contains("com.example.ContactCreated");
    }
}
