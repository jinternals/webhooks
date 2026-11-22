package com.jinternals.webhooks.sample.consumer.nats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Zero-webhook-loss consumer sample backed by NATS JetStream + NATS KeyValue idempotency:
 *
 * <ul>
 *   <li>{@link NatsKvIdempotencyGuard} implements the reserve-then-commit {@code IdempotencyGuard} SPI
 *       across all consumer instances using a NATS JetStream KeyValue bucket;</li>
 *   <li>{@link DurableInboxHandlers} runs in {@code sync} mode and awaits a durable NATS JetStream
 *       {@code PublishAck} on {@code WEBHOOKS_INBOUND} <em>before</em> HTTP {@code 204} is returned (if NATS
 *       is unreachable, {@code 500} is returned and the reservation is released so the sender retries);</li>
 *   <li>{@link NatsInboundWorker} drains {@code WEBHOOKS_INBOUND} with explicit {@code ack} /
 *       {@code nakWithDelay} and moves poison messages to {@code WEBHOOKS_INBOUND_DLQ}.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(NatsConsumerProperties.class)
public class ConsumerNatsSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerNatsSampleApplication.class, args);
    }
}
