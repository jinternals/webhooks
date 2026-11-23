package com.jinternals.webhooks.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * The Standard Webhooks envelope — the shared wire format for both sides. On the consumer side it is
 * used for routing before the concrete {@code @WebhookType} class is known (the {@code data} node is
 * deserialized into the target event class afterwards); on the producer side it is the object that is
 * serialized, signed, and POSTed.
 *
 * <pre>
 * {
 *   "type": "contact.created",
 *   "timestamp": "2022-11-03T20:26:10.344522Z",
 *   "data": { ... }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Envelope {

    private String type;
    private String timestamp; // ISO 8601 event occurrence time (body timestamp)
    private JsonNode data;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }
}
