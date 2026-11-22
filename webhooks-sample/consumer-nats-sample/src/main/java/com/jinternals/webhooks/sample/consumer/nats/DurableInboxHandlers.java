package com.jinternals.webhooks.sample.consumer.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.sample.consumer.nats.events.ContactCreated;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Synchronous {@code @WebhookHandler} that bridges verified inbound webhooks into the durable NATS JetStream
 * {@code WEBHOOKS_INBOUND} stream <strong>before</strong> HTTP {@code 204} is returned.
 *
 * <p>If NATS is unreachable, this handler throws so {@code WebhookReceivePipeline} calls
 * {@code IdempotencyGuard.release(webhookId)} and returns {@code 500} — prompting the sender's NATS relay to
 * retry later with zero webhook loss.
 */
@Component
public class DurableInboxHandlers {

    private static final Logger log = LoggerFactory.getLogger(DurableInboxHandlers.class);

    private final JetStream jetStream;
    private final ObjectMapper objectMapper;
    private final NatsConsumerProperties properties;

    public DurableInboxHandlers(JetStream jetStream, ObjectMapper objectMapper, NatsConsumerProperties properties) {
        this.jetStream = jetStream;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @WebhookHandler
    public void onContactCreated(ContactCreated event, WebhookMetadata metadata) {
        InboundWebhookMessage inbound = new InboundWebhookMessage(
                metadata.webhookId(),
                metadata.type(),
                metadata.endpointPath(),
                metadata.attemptTimestamp(),
                metadata.eventTimestamp(),
                MDC.get(TraceContextFilter.TRACE_MDC_KEY),
                event);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(inbound);
            PublishOptions.Builder opts = PublishOptions.builder().stream(properties.getStream());
            if (metadata.webhookId() != null && !metadata.webhookId().isBlank()) {
                opts.messageId(metadata.webhookId());
            }
            PublishAck ack = jetStream.publish(properties.getSubject(), bytes, opts.build());
            log.info(
                    "Persisted verified webhook id={} type={} to NATS stream={} seq={} event={}",
                    metadata.webhookId(),
                    metadata.type(),
                    ack.getStream(),
                    ack.getSeqno(),
                    metadata.redactedEvent());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to persist inbound webhook " + metadata.webhookId() + " to NATS JetStream", e);
        }
    }
}
