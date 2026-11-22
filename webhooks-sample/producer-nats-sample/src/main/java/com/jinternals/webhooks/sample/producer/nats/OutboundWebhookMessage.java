package com.jinternals.webhooks.sample.producer.nats;

/**
 * Durable payload stored in {@code WEBHOOKS_OUTBOUND}. Notice that {@code webhook-signature} and
 * {@code webhook-timestamp} are intentionally <em>not</em> frozen here: {@link NatsOutboundRelayWorker}
 * computes a fresh attempt timestamp and signature on each delivery attempt while keeping {@link #webhookId}
 * and {@link #body} (including its original event occurrence {@code timestamp}) unchanged.
 */
public record OutboundWebhookMessage(String url, String webhookId, String body, String traceId) {}
