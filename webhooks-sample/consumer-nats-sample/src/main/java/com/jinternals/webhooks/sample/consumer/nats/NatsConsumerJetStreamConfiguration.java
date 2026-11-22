package com.jinternals.webhooks.sample.consumer.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sample.nats.connect-on-startup", havingValue = "true", matchIfMissing = true)
public class NatsConsumerJetStreamConfiguration {

    @Bean(destroyMethod = "close")
    Connection natsConnection(NatsConsumerProperties properties) throws IOException, InterruptedException {
        return Nats.connect(properties.getUrl());
    }

    @Bean
    JetStream jetStream(Connection connection, NatsConsumerProperties properties)
            throws IOException, JetStreamApiException {
        JetStreamManagement jsm = connection.jetStreamManagement();
        ensureStream(
                jsm,
                StreamConfiguration.builder()
                        .name(properties.getStream())
                        .subjects(properties.getSubject())
                        .storageType(StorageType.File)
                        .retentionPolicy(RetentionPolicy.WorkQueue)
                        .duplicateWindow(Duration.ofMinutes(10))
                        .build());
        ensureStream(
                jsm,
                StreamConfiguration.builder()
                        .name(properties.getDlqStream())
                        .subjects(properties.getDlqSubject())
                        .storageType(StorageType.File)
                        .retentionPolicy(RetentionPolicy.Limits)
                        .build());

        jsm.addOrUpdateConsumer(
                properties.getStream(),
                ConsumerConfiguration.builder()
                        .durable(properties.getDurableConsumer())
                        .filterSubject(properties.getSubject())
                        .ackPolicy(AckPolicy.Explicit)
                        .ackWait(Duration.ofSeconds(30))
                        .maxDeliver(properties.getMaxDeliver())
                        .build());
        return connection.jetStream();
    }

    @Bean
    KeyValue idempotencyKeyValue(Connection connection, NatsConsumerProperties properties)
            throws IOException, JetStreamApiException {
        KeyValueManagement kvm = connection.keyValueManagement();
        KeyValueConfiguration kvConfig = KeyValueConfiguration.builder()
                .name(properties.getIdempotencyBucket())
                .storageType(StorageType.File)
                .ttl(properties.getIdempotencyTtl())
                .maxHistoryPerKey(1)
                .build();
        try {
            kvm.getStatus(properties.getIdempotencyBucket());
            kvm.update(kvConfig);
        } catch (JetStreamApiException notFound) {
            kvm.create(kvConfig);
        }
        return connection.keyValue(properties.getIdempotencyBucket());
    }

    @Bean
    JetStreamSubscription inboundPullSubscription(JetStream jetStream, NatsConsumerProperties properties)
            throws IOException, JetStreamApiException {
        return jetStream.subscribe(
                properties.getSubject(),
                PullSubscribeOptions.bind(properties.getStream(), properties.getDurableConsumer()));
    }

    private static void ensureStream(JetStreamManagement jsm, StreamConfiguration config)
            throws IOException, JetStreamApiException {
        try {
            jsm.getStreamInfo(config.getName());
            jsm.updateStream(config);
        } catch (JetStreamApiException notFound) {
            jsm.addStream(config);
        }
    }
}
