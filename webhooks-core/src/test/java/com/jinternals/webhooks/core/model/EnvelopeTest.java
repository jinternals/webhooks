package com.jinternals.webhooks.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    @Test
    void roundTripsTypeTimestampAndData() {
        JsonNode data = JsonNodeFactory.instance.objectNode().put("id", "c-1");

        Envelope envelope = new Envelope();
        envelope.setType("contact.created");
        envelope.setTimestamp("2022-11-03T20:26:10.344522Z");
        envelope.setData(data);

        assertThat(envelope.getType()).isEqualTo("contact.created");
        assertThat(envelope.getTimestamp()).isEqualTo("2022-11-03T20:26:10.344522Z");
        assertThat(envelope.getData()).isSameAs(data);
    }
}
