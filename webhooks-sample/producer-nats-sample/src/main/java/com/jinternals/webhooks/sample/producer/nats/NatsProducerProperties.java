package com.jinternals.webhooks.sample.producer.nats;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("sample.nats")
public class NatsProducerProperties {

    private String url = "nats://localhost:4222";
    private boolean connectOnStartup = true;
    private boolean workerAutoStart = true;
    private String stream = "WEBHOOKS_OUTBOUND";
    private String subject = "webhooks.outbound.send";
    private String dlqStream = "WEBHOOKS_OUTBOUND_DLQ";
    private String dlqSubject = "webhooks.outbound.dlq.send";
    private String durableConsumer = "webhooks-outbound-relay";
    private int maxDeliver = 5;
    private Duration baseRetryDelay = Duration.ofSeconds(1);
    private Duration maxRetryDelay = Duration.ofSeconds(30);
}
