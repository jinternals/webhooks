package com.jinternals.webhooks.producer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.sign.WebhookSigner;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookPublisherTest {

    @WebhookType("contact.created")
    static class ContactCreated {
        public String id = "c-1";
        public String name = "Ada";
    }

    static class NotAnEvent {
        public String id = "x";
    }

    private ObjectMapper objectMapper;
    private WebhookSigner signer;
    private WebhookTransport transport;
    private SecretProvider<Destination> secretProvider;
    private WebhookProducerProperties properties;
    private WebhookPublisher publisher;

    private Destination crm;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        signer = mock(WebhookSigner.class);
        when(signer.isEnabled()).thenReturn(true); // default: signing on (the real default is HmacWebhookSigner)
        transport = mock(WebhookTransport.class);
        secretProvider = mock(SecretProvider.class);
        properties = new WebhookProducerProperties();

        crm = new Destination();
        crm.setUrl("https://crm.example.com/webhooks");
        crm.setSecret("whsec_abc");
        properties.setDestinations(Map.of("crm", crm));

        publisher = new WebhookPublisher(objectMapper, signer, transport, secretProvider, properties);
    }

    @Test
    void rejectsUnknownDestination() {
        assertThatThrownBy(() -> publisher.publish("nope", new ContactCreated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown webhook destination: nope");
    }

    @Test
    void rejectsNullEvent() {
        // A null event must fail with a clear IllegalArgumentException naming the destination, not an opaque
        // NPE from event.getClass() deeper in the pipeline. The guard runs first, before destination lookup.
        assertThatThrownBy(() -> publisher.publish("crm", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish a null event")
                .hasMessageContaining("crm");
    }

    @Test
    void nullEventGuardRunsBeforeDestinationLookup() {
        // Even for an unknown destination, the null-event guard fires first: the message is the null-event
        // one, not "Unknown webhook destination". Pins the guard ordering (kills a reordering/removal mutant).
        assertThatThrownBy(() -> publisher.publish("nope", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish a null event");
    }

    @Test
    void rejectsEventWithoutTypeAnnotation() {
        assertThatThrownBy(() -> publisher.publish("crm", new NotAnEvent()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    @Test
    void rejectsWhenNoSecretResolved() {
        when(secretProvider.secretsFor(crm)).thenReturn(List.of());
        assertThatThrownBy(() -> publisher.publish("crm", new ContactCreated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No signing secret configured for webhook destination: crm");
    }

    @Test
    void rejectsACustomProviderThatReturnsANullSecretEntry() {
        // A misbehaving custom SecretProvider returns a non-empty list whose entry is null: the publisher
        // must refuse (naming the destination) rather than pass null to the signer or send unsigned. Use a
        // list that can hold null (List.of cannot).
        List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        when(secretProvider.secretsFor(crm)).thenReturn(withNull);

        assertThatThrownBy(() -> publisher.publish("crm", new ContactCreated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null/blank signing secret")
                .hasMessageContaining("crm");
        verifyNoInteractions(transport); // nothing was sent
    }

    @Test
    void rejectsACustomProviderThatReturnsABlankSecretEntry() {
        // Same defense for a blank (whitespace) entry.
        List<String> withBlank = new java.util.ArrayList<>();
        withBlank.add("   ");
        when(secretProvider.secretsFor(crm)).thenReturn(withBlank);

        assertThatThrownBy(() -> publisher.publish("crm", new ContactCreated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null/blank signing secret");
        verifyNoInteractions(transport);
    }

    @Test
    void wrapsSerializationFailure() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.createObjectNode()).thenReturn(new ObjectMapper().createObjectNode());
        when(failing.valueToTree(org.mockito.ArgumentMatchers.any())).thenReturn(new ObjectMapper().createObjectNode());
        when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new JsonMappingException(null, "boom"));
        when(secretProvider.secretsFor(crm)).thenReturn(List.of("whsec_abc"));

        WebhookPublisher failingPublisher =
                new WebhookPublisher(failing, signer, transport, secretProvider, properties);

        assertThatThrownBy(() -> failingPublisher.publish("crm", new ContactCreated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize webhook payload");
    }

    @Test
    void buildsSignsAndSendsEnvelope() throws Exception {
        when(secretProvider.secretsFor(crm)).thenReturn(List.of("whsec_abc"));
        when(signer.sign(eq("whsec_abc"), isNull(), anyString(), anyLong(), anyString()))
                .thenReturn("v1,sig");

        publisher.publish("crm", new ContactCreated());

        // The exact body is signed, then sent — capture both and assert they match.
        ArgumentCaptor<String> signedBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> signedId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> signedTs = ArgumentCaptor.forClass(Long.class);
        verify(signer).sign(eq("whsec_abc"), isNull(), signedId.capture(), signedTs.capture(), signedBody.capture());

        assertThat(signedId.getValue()).startsWith("msg_");

        ArgumentCaptor<String> sentUrl = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> sentHeaders = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sentBody = ArgumentCaptor.forClass(String.class);
        verify(transport).send(sentUrl.capture(), sentHeaders.capture(), sentBody.capture());

        assertThat(sentUrl.getValue()).isEqualTo("https://crm.example.com/webhooks");
        assertThat(sentBody.getValue()).isEqualTo(signedBody.getValue());

        var envelope = objectMapper.readTree(sentBody.getValue());
        assertThat(envelope.get("type").asText()).isEqualTo("contact.created");
        assertThat(envelope.get("timestamp").asText()).isNotBlank();
        assertThat(envelope.get("data").get("id").asText()).isEqualTo("c-1");
        assertThat(envelope.get("data").get("name").asText()).isEqualTo("Ada");

        Map<String, List<String>> headers = sentHeaders.getValue();
        assertThat(headers.get(WebhookHeaders.ID)).containsExactly(signedId.getValue());
        assertThat(headers.get(WebhookHeaders.TIMESTAMP)).containsExactly(Long.toString(signedTs.getValue()));
        // Single secret: exactly one signature, no leading/trailing space.
        assertThat(headers.get(WebhookHeaders.SIGNATURE)).containsExactly("v1,sig");
    }

    @Test
    void whenSigningDisabledSendsNoSignatureHeaderAndRequiresNoSecret() {
        // signing-enabled=false → NoOpWebhookSigner (isEnabled()==false). The publisher must: never call the
        // SecretProvider, never call signer.sign, and send NO webhook-signature header — while still sending
        // the id/timestamp headers and the body. Kills mutants that keep signing, keep the secret check, or
        // keep emitting the signature header when signing is off.
        when(signer.isEnabled()).thenReturn(false);

        publisher.publish("crm", new ContactCreated());

        verifyNoInteractions(secretProvider);
        verify(signer, org.mockito.Mockito.never())
                .sign(anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyLong(), anyString());

        ArgumentCaptor<String> sentUrl = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> sentHeaders = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sentBody = ArgumentCaptor.forClass(String.class);
        verify(transport).send(sentUrl.capture(), sentHeaders.capture(), sentBody.capture());

        Map<String, List<String>> headers = sentHeaders.getValue();
        assertThat(headers).doesNotContainKey(WebhookHeaders.SIGNATURE);
        assertThat(headers.get(WebhookHeaders.ID)).hasSize(1).allSatisfy(id -> assertThat(id)
                .startsWith("msg_"));
        assertThat(headers.get(WebhookHeaders.TIMESTAMP)).hasSize(1);
        assertThat(sentUrl.getValue()).isEqualTo("https://crm.example.com/webhooks");
        // Body is still the full signed-shape envelope, just delivered unsigned.
        assertThat(sentBody.getValue()).contains("contact.created");
    }

    @Test
    void dualSignsWithEverySecretJoinedBySpace() {
        when(secretProvider.secretsFor(crm)).thenReturn(List.of("whsec_new", "whsec_old"));
        when(signer.sign(eq("whsec_new"), isNull(), anyString(), anyLong(), anyString()))
                .thenReturn("v1,newsig");
        when(signer.sign(eq("whsec_old"), isNull(), anyString(), anyLong(), anyString()))
                .thenReturn("v1,oldsig");

        publisher.publish("crm", new ContactCreated());

        // Same id, timestamp, and payload signed with each secret.
        ArgumentCaptor<String> newBody = ArgumentCaptor.forClass(String.class);
        verify(signer).sign(eq("whsec_new"), isNull(), anyString(), anyLong(), newBody.capture());
        ArgumentCaptor<String> oldBody = ArgumentCaptor.forClass(String.class);
        verify(signer).sign(eq("whsec_old"), isNull(), anyString(), anyLong(), oldBody.capture());
        assertThat(oldBody.getValue()).isEqualTo(newBody.getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> sentHeaders = ArgumentCaptor.forClass(Map.class);
        verify(transport).send(anyString(), sentHeaders.capture(), anyString());

        // Both signatures in one space-separated header, primary first (Standard Webhooks multi-sig).
        assertThat(sentHeaders.getValue().get(WebhookHeaders.SIGNATURE)).containsExactly("v1,newsig v1,oldsig");
    }
}
