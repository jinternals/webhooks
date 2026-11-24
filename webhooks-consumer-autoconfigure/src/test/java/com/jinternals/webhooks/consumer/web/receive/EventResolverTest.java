package com.jinternals.webhooks.consumer.web.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.config.WebhookProperties.UnknownTypePolicy;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.web.error.InvalidPayloadException;
import com.jinternals.webhooks.consumer.web.error.PayloadValidationException;
import com.jinternals.webhooks.consumer.web.error.UnknownTypeException;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test for {@link EventResolver} (SPEC.md §5, §6a): routing (accept + model) then strict
 * deserialization + validation, resolving to {@link Resolution.Accepted} or {@link Resolution.Ignored},
 * or throwing under the {@code REJECT} policy / on a payload failure.
 */
class EventResolverTest {

    static class ContactCreated {
        public String id;
    }

    private final WebhookTypeRegistry typeRegistry = mock(WebhookTypeRegistry.class);
    private final PayloadValidator payloadValidator = mock(PayloadValidator.class);
    private final WebhookProperties properties = new WebhookProperties();
    private final EventResolver resolver =
            new EventResolver(typeRegistry, payloadValidator, new JsonMapper(), properties);

    private Endpoint endpoint(List<String> types) {
        Endpoint endpoint = new Endpoint();
        endpoint.setPath("/webhooks/crm");
        endpoint.setTypes(types);
        return endpoint;
    }

    private Envelope envelope(String type, String dataJson) throws Exception {
        Envelope envelope = new Envelope();
        envelope.setType(type);
        envelope.setData(new JsonMapper().readTree(dataJson));
        return envelope;
    }

    @Test
    void acceptedTypeResolvesToTheTypeAndValidatedEvent() throws Exception {
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        Resolution resolution = resolver.resolve(endpoint(List.of()), envelope("contact.created", "{\"id\":\"c-1\"}"));

        assertThat(resolution).isInstanceOf(Resolution.Accepted.class);
        Resolution.Accepted accepted = (Resolution.Accepted) resolution;
        assertThat(accepted.type()).isEqualTo("contact.created");
        assertThat(accepted.event()).isInstanceOf(ContactCreated.class);
        assertThat(((ContactCreated) accepted.event()).id).isEqualTo("c-1");
    }

    @Test
    void missingTypeIsAnInvalidPayload() {
        // A signed envelope with no routable type can't be matched: a 400 regardless of policy.
        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope(null, "{}")))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing a 'type'");
    }

    @Test
    void blankTypeIsAnInvalidPayload() {
        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope("   ", "{}")))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing a 'type'");
    }

    @Test
    void missingTypeIsAnInvalidPayloadEvenUnderRejectPolicy() {
        // Proves the missing-type guard runs before the allow-list/registry policy branch: a null type
        // is a 400 (InvalidPayloadException), not an UnknownTypeException, even under REJECT.
        properties.getConsumer().setUnknownTypePolicy(UnknownTypePolicy.REJECT);
        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope(null, "{}")))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void typeNotInEndpointAllowListIsIgnoredUnderAckPolicy() throws Exception {
        Resolution resolution =
                resolver.resolve(endpoint(List.of("contact.updated")), envelope("contact.created", "{}"));

        assertThat(resolution).isInstanceOf(Resolution.Ignored.class);
    }

    @Test
    void unmodelledTypeIsIgnoredUnderAckPolicy() throws Exception {
        when(typeRegistry.classOf("contact.created")).thenReturn(null);

        Resolution resolution = resolver.resolve(endpoint(List.of()), envelope("contact.created", "{}"));

        assertThat(resolution).isInstanceOf(Resolution.Ignored.class);
    }

    @Test
    void typeNotAllowListedThrowsUnderRejectPolicy() {
        properties.getConsumer().setUnknownTypePolicy(UnknownTypePolicy.REJECT);

        assertThatThrownBy(
                        () -> resolver.resolve(endpoint(List.of("contact.updated")), envelope("contact.created", "{}")))
                .isInstanceOf(UnknownTypeException.class);
    }

    @Test
    void unmodelledTypeThrowsUnderRejectPolicy() {
        properties.getConsumer().setUnknownTypePolicy(UnknownTypePolicy.REJECT);
        when(typeRegistry.classOf("contact.created")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope("contact.created", "{}")))
                .isInstanceOf(UnknownTypeException.class);
    }

    @Test
    void unknownJsonFieldFailsStrictDeserializationAsInvalidPayload() {
        // A strict-deserialization failure (unknown field) is a sender 400, wrapped as
        // InvalidPayloadException carrying the type — not an opaque exception mapped to 500.
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(() ->
                        resolver.resolve(endpoint(List.of()), envelope("contact.created", "{\"id\":\"c-1\",\"x\":1}")))
                .isInstanceOf(InvalidPayloadException.class)
                .extracting("type")
                .isEqualTo("contact.created");
    }

    @Test
    void missingDataNodeIsAnInvalidPayloadNotAServerError() throws Exception {
        // A verified envelope for a modelled type but with no 'data' field (envelope.getData() == null) must
        // be a 400 (InvalidPayloadException carrying the type), NOT a 500. Jackson would bind a null data node
        // to a null event, which Bean Validation cannot validate (it throws IllegalArgumentException); without
        // this guard that escapes as a 500 and the sender retries a payload that can never succeed.
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        Envelope envelope = new Envelope();
        envelope.setType("contact.created"); // data left null

        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing required 'data'")
                .extracting("type")
                .isEqualTo("contact.created");
    }

    @Test
    void jsonNullDataNodeIsAnInvalidPayloadNotAServerError() throws Exception {
        // "data": null parses to a JSON-null node (not a Java null): data.isNull() must also be rejected as a
        // 400, pinning the second arm of the guard so a mutant dropping either check is killed.
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope("contact.created", "null")))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing required 'data'");
    }

    @Test
    void dataNodeCoercedToNullByLenientMapperIsRejectedAsInvalidPayloadBeforeValidation() throws Exception {
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        JsonMapper lenient = JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .build();
        EventResolver lenientResolver = new EventResolver(typeRegistry, payloadValidator, lenient, properties);

        assertThatThrownBy(() -> lenientResolver.resolve(endpoint(List.of()), envelope("contact.created", "\"\"")))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("deserialized to null");
        org.mockito.Mockito.verifyNoInteractions(payloadValidator);
    }

    @Test
    void malformedDataValueFailsAsInvalidPayload() {
        // A data value that cannot bind (object where a String is expected) is a Jackson failure
        // wrapped as InvalidPayloadException (400).
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(() ->
                        resolver.resolve(endpoint(List.of()), envelope("contact.created", "{\"id\":{\"nested\":1}}")))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void validationFailurePropagates() {
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        doThrow(new PayloadValidationException("contact.created", List.of("id must not be blank")))
                .when(payloadValidator)
                .validate(eq("contact.created"), any());

        assertThatThrownBy(() -> resolver.resolve(endpoint(List.of()), envelope("contact.created", "{\"id\":\"c-1\"}")))
                .isInstanceOf(PayloadValidationException.class);
    }
}
