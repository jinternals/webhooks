package com.jinternals.webhooks.sample.producer.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
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
public class NatsJetStreamConfiguration {

    @Bean(destroyMethod = "close")
    Connection natsConnection(NatsProducerProperties properties) throws IOException, InterruptedException {
        return Nats.connect(properties.getUrl());
    }

    @Bean
    JetStream jetStream(Connection connection, NatsProducerProperties properties)
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
    JetStreamSubscription outboundPullSubscription(JetStream jetStream, NatsProducerProperties properties)
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
