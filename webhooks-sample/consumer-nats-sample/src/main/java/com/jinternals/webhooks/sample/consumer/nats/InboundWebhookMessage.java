package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.sample.consumer.nats.events.ContactCreated;
import java.time.Instant;

public record InboundWebhookMessage(
        String webhookId,
        String type,
        String endpointPath,
        Instant attemptTimestamp,
        Instant eventTimestamp,
        String traceId,
        ContactCreated contact) {}
