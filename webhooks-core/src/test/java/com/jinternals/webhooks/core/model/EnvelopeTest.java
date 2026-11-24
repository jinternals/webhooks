package com.jinternals.webhooks.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class EnvelopeTest {

    @Test
    void roundTripsTypeTimestampAndDataInCanonicalPropertyOrder() throws Exception {
        JsonNode data = JsonNodeFactory.instance.objectNode().put("id", "c-1");

        Envelope envelope = new Envelope();
        envelope.setType("contact.created");
        envelope.setTimestamp("2022-11-03T20:26:10.344522Z");
        envelope.setData(data);

        assertThat(envelope.getType()).isEqualTo("contact.created");
        assertThat(envelope.getTimestamp()).isEqualTo("2022-11-03T20:26:10.344522Z");
        assertThat(envelope.getData()).isSameAs(data);

        String json = new JsonMapper().writeValueAsString(envelope);
        assertThat(json)
                .isEqualTo(
                        "{\"type\":\"contact.created\",\"timestamp\":\"2022-11-03T20:26:10.344522Z\",\"data\":{\"id\":\"c-1\"}}");
    }
}
