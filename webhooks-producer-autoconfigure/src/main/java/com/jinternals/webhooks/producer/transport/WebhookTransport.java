package com.jinternals.webhooks.producer.transport;

import java.util.List;
import java.util.Map;

/**
 * Pluggable delivery SPI for outbound webhooks (see SPEC.md §19). The default
 * {@link RestClientWebhookTransport} POSTs synchronously via Spring's {@code RestClient}; override with
 * a {@code @Bean WebhookTransport} to send asynchronously, through a queue, or with your own retry
 * policy. The producer core deliberately ships <strong>no</strong> retry or persistence.
 */
public interface WebhookTransport {

    /**
     * Deliver a fully-signed webhook request.
     *
     * @param url     the absolute destination URL
     * @param headers the request headers (including the Standard Webhooks {@code webhook-*} headers)
     *                as name -&gt; list of values
     * @param body    the raw JSON body exactly as signed
     * @throws WebhookDeliveryException if delivery fails (non-2xx response or transport error)
     */
    void send(String url, Map<String, List<String>> headers, String body);
}
