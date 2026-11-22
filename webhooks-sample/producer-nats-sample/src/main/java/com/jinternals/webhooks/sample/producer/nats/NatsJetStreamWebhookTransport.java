package com.jinternals.webhooks.sample.producer.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Durable {@link WebhookTransport} backed by NATS JetStream ({@code WEBHOOKS_OUTBOUND}).
 *
 * <p>Replaces the library's default synchronous HTTP transport via {@code @ConditionalOnMissingBean}:
 * instead of making a remote HTTP call on the caller's thread (where a JVM crash or prolonged receiver
 * outage would lose the event), it persists the message to NATS JetStream with {@code Nats-Msg-Id} set to
 * {@code webhook-id} and returns once the broker confirms {@link PublishAck}. {@link NatsOutboundRelayWorker}
 * then delivers it asynchronously with fresh per-attempt Standard Webhooks signatures.
 */
@Component
public class NatsJetStreamWebhookTransport implements WebhookTransport {

    public static final String TRACE_MDC_KEY = "traceId";

    private static final Logger log = LoggerFactory.getLogger(NatsJetStreamWebhookTransport.class);

    private final JetStream jetStream;
    private final ObjectMapper objectMapper;
    private final DestinationUrlValidator urlValidator;
    private final NatsProducerProperties properties;

    public NatsJetStreamWebhookTransport(
            JetStream jetStream,
            ObjectMapper objectMapper,
            DestinationUrlValidator urlValidator,
            NatsProducerProperties properties) {
        this.jetStream = jetStream;
        this.objectMapper = objectMapper;
        this.urlValidator = urlValidator;
        this.properties = properties;
    }

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        // Validate destination URL eagerly before accepting onto the stream.
        urlValidator.validate(url);

        List<String> ids = headers.get(WebhookHeaders.ID);
        String webhookId = (ids != null && !ids.isEmpty()) ? ids.get(0) : null;
        String traceId = MDC.get(TRACE_MDC_KEY);

        try {
            byte[] payload = objectMapper.writeValueAsBytes(new OutboundWebhookMessage(url, webhookId, body, traceId));
            PublishOptions options = PublishOptions.builder().stream(properties.getStream())
                    .messageId(webhookId)
                    .build();
            PublishAck ack = jetStream.publish(properties.getSubject(), payload, options);
            log.info(
                    "Enqueued outbound webhook id={} stream={} seq={} duplicate={} target={}",
                    webhookId,
                    ack.getStream(),
                    ack.getSeqno(),
                    ack.isDuplicate(),
                    SafeUrl.sanitize(url));
        } catch (Exception e) {
            throw new WebhookDeliveryException(
                    "Failed to persist outbound webhook to NATS JetStream for " + SafeUrl.sanitize(url), e);
        }
    }
}
