package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.AttemptAwareWebhookTransport;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/**
 * Durable {@link AttemptAwareWebhookTransport} backed by NATS JetStream ({@code WEBHOOKS_OUTBOUND}).
 *
 * <p>Replaces the library's default synchronous HTTP transport via {@code @ConditionalOnMissingBean}:
 * instead of making a remote HTTP call on the caller's thread (where a JVM crash or prolonged receiver
 * outage would lose the event), it persists the message to NATS JetStream with {@code Nats-Msg-Id} set to
 * {@code webhook-id} and returns once the broker confirms {@link PublishAck}. Implementing
 * {@link AttemptAwareWebhookTransport#sendPerAttempt(String, String, Supplier, String)} avoids computing a
 * throwaway signature on the enqueue thread because {@link NatsOutboundRelayWorker} computes a fresh
 * Standard Webhooks signature on every outbound HTTP delivery attempt.
 */
@Component
public class NatsJetStreamWebhookTransport implements AttemptAwareWebhookTransport {

    public static final String TRACE_MDC_KEY = "traceId";

    private static final Logger log = LoggerFactory.getLogger(NatsJetStreamWebhookTransport.class);

    private final JetStream jetStream;
    private final ObjectWriter messageWriter;
    private final DestinationUrlValidator urlValidator;
    private final NatsProducerProperties properties;

    public NatsJetStreamWebhookTransport(
            JetStream jetStream,
            ObjectMapper objectMapper,
            DestinationUrlValidator urlValidator,
            NatsProducerProperties properties) {
        this.jetStream = jetStream;
        this.messageWriter = objectMapper.writerFor(OutboundWebhookMessage.class);
        this.urlValidator = urlValidator;
        this.properties = properties;
    }

    @Override
    public void sendPerAttempt(
            String url, String messageId, Supplier<Map<String, List<String>>> headersSupplier, String body) {
        enqueue(url, messageId, body);
    }

    @Override
    public void sendPerAttempt(String url, Supplier<Map<String, List<String>>> headersSupplier, String body) {
        send(url, headersSupplier.get(), body);
    }

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        List<String> ids = headers.get(WebhookHeaders.ID);
        String webhookId = (ids != null && !ids.isEmpty()) ? ids.get(0) : null;
        enqueue(url, webhookId, body);
    }

    private void enqueue(String url, String webhookId, String body) {
        // Validate destination URL eagerly before accepting onto the stream.
        urlValidator.validate(url);
        String traceId = MDC.get(TRACE_MDC_KEY);

        try {
            byte[] payload = messageWriter.writeValueAsBytes(new OutboundWebhookMessage(url, webhookId, body, traceId));
            PublishOptions options = PublishOptions.builder()
                    .expectedStream(properties.getStream())
                    .messageId(webhookId)
                    .build();
            PublishAck ack = jetStream.publish(properties.getSubject(), payload, options);
            if (log.isInfoEnabled()) {
                log.info(
                        "Enqueued outbound webhook id={} stream={} seq={} duplicate={} target={}",
                        webhookId,
                        ack.getStream(),
                        ack.getSeqno(),
                        ack.isDuplicate(),
                        SafeUrl.sanitize(url));
            }
        } catch (Exception e) {
            throw new WebhookDeliveryException(
                    "Failed to persist outbound webhook to NATS JetStream for " + SafeUrl.sanitize(url), e);
        }
    }
}
