package com.jinternals.webhooks.sample.producer.spi;

import com.jinternals.webhooks.producer.transport.WebhookTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sample {@link WebhookTransport} SPI implementation that hands signed webhook envelopes to an asynchronous
 * queue instead of performing synchronous HTTP delivery on the caller's thread (see
 * {@code docs/reference/beans-and-spis.md}).
 */
public class QueueingWebhookTransport implements WebhookTransport {

    public record OutboundMessage(String url, Map<String, List<String>> headers, String body) {}

    private final BlockingQueue<OutboundMessage> queue = new LinkedBlockingQueue<>();

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        queue.add(new OutboundMessage(url, Map.copyOf(headers), body));
    }

    public BlockingQueue<OutboundMessage> queue() {
        return queue;
    }
}
