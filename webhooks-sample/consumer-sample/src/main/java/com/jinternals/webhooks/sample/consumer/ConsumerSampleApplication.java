package com.jinternals.webhooks.sample.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone <strong>consumer</strong> sample. The listener comes entirely from {@code webhooks-consumer}
 * via Spring Boot auto-configuration; this app only supplies {@code @WebhookType} events,
 * {@code @WebhookHandler} beans, an optional {@code MetadataContributor}, and {@code webhooks.consumer.*}
 * config.
 *
 * <p>No component-scanning of the library is required.
 */
@SpringBootApplication
public class ConsumerSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerSampleApplication.class, args);
    }
}
