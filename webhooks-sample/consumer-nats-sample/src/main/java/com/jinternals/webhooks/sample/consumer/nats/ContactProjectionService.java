package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.core.redact.WebhookRedactor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Downstream domain projection service. Upserts idempotently by {@code webhookId} (mirroring an SQL
 * {@code INSERT ... ON CONFLICT DO NOTHING} / upsert) so even if a worker crashes in the narrow window after
 * the DB write and before {@code msg.ackSync}, JetStream's redelivery to a sibling consumer instance is a
 * safe no-op.
 */
@Service
public class ContactProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ContactProjectionService.class);

    private final Map<String, InboundWebhookMessage> processedByWebhookId = new ConcurrentHashMap<>();
    private volatile boolean failNext = false;

    public void process(InboundWebhookMessage message) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("Simulated transient downstream DB outage");
        }
        String key = message.webhookId() != null
                ? message.webhookId()
                : message.contact().getId();
        if (processedByWebhookId.putIfAbsent(key, message) == null) {
            log.info(
                    "contact.created processed from NATS inbox id={} webhookId={} event={}",
                    message.contact().getId(),
                    message.webhookId(),
                    WebhookRedactor.toRedactedString(message.contact()));
        } else {
            log.info("Idempotent no-op for redelivered NATS inbox message webhookId={}", message.webhookId());
        }
    }

    public void failNextInvocation() {
        this.failNext = true;
    }

    public List<InboundWebhookMessage> processed() {
        return List.copyOf(processedByWebhookId.values());
    }

    public void clear() {
        processedByWebhookId.clear();
    }
}
