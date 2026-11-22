package com.jinternals.webhooks.sample.producer;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooksV2a;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone <strong>producer</strong> sample. {@code @EnableWebhookClients} opts in two clients
 * explicitly ({@code clients = ...}, the zero-scan path): {@link CrmWebhooks} (the {@code demo}
 * destination, {@code v1} symmetric HMAC) and {@link CrmWebhooksV2a} (the {@code demo-v2a} destination,
 * {@code v2a} asymmetric Ed25519). {@code POST /publish/contact-created} drives the {@code v1} path and
 * {@code POST /publish/contact-created-v2a} drives the {@code v2a} path (see SPEC.md §19 / §19i).
 *
 * <p>The sender library is entirely auto-configured — no component-scanning of the library is required.
 */
@SpringBootApplication
@EnableWebhookClients(clients = {CrmWebhooks.class, CrmWebhooksV2a.class})
public class ProducerSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerSampleApplication.class, args);
    }
}
