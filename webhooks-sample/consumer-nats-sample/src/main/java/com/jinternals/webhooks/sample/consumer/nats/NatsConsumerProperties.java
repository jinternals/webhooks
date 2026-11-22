package com.jinternals.webhooks.sample.consumer.nats;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("sample.nats")
public class NatsConsumerProperties {

    private String url = "nats://localhost:4222";
    private boolean connectOnStartup = true;
    private boolean workerAutoStart = true;
    private String stream = "WEBHOOKS_INBOUND";
    private String subject = "webhooks.inbound.events";
    private String dlqStream = "WEBHOOKS_INBOUND_DLQ";
    private String dlqSubject = "webhooks.inbound.dlq.events";
    private String durableConsumer = "webhooks-inbound-worker";
    private String idempotencyBucket = "WEBHOOK_IDEMPOTENCY";
    private Duration idempotencyTtl = Duration.ofHours(24);
    private int maxDeliver = 5;
    private Duration retryDelay = Duration.ofSeconds(2);
}
