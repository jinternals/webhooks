package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.sample.producer.nats.client.CrmWebhooks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Zero-webhook-loss producer sample backed by NATS JetStream:
 *
 * <ul>
 *   <li>{@link NatsJetStreamWebhookTransport} replaces the default HTTP {@code WebhookTransport} so
 *       {@code @WebhookClient} calls persist envelopes to NATS JetStream ({@code WEBHOOKS_OUTBOUND}) with
 *       broker-side {@code Nats-Msg-Id} deduplication;</li>
 *   <li>{@link NatsOutboundRelayWorker} pulls messages from JetStream, generates a <strong>fresh
 *       {@code webhook-timestamp} and {@code webhook-signature}</strong> on every delivery attempt (keeping
 *       delayed retries valid against the receiver's 5m replay window while preserving {@code webhook-id}
 *       and the event's original envelope {@code timestamp}), and routes fatal/exhausted failures to
 *       {@code WEBHOOKS_OUTBOUND_DLQ} + {@link DeadLetterDeliveryListener}.</li>
 * </ul>
 */
@SpringBootApplication
@EnableWebhookClients(clients = CrmWebhooks.class)
@EnableConfigurationProperties(NatsProducerProperties.class)
public class ProducerNatsSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerNatsSampleApplication.class, args);
    }
}
