package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.core.redact.WebhookRedactor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ContactProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ContactProjectionService.class);

    private final List<InboundWebhookMessage> processed = new CopyOnWriteArrayList<>();
    private volatile boolean failNext = false;

    public void process(InboundWebhookMessage message) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("Simulated transient downstream DB outage");
        }
        processed.add(message);
        log.info(
                "contact.created processed from NATS inbox id={} webhookId={} event={}",
                message.contact().getId(),
                message.webhookId(),
                WebhookRedactor.toRedactedString(message.contact()));
    }

    public void failNextInvocation() {
        this.failNext = true;
    }

    public List<InboundWebhookMessage> processed() {
        return List.copyOf(processed);
    }

    public void clear() {
        processed.clear();
    }
}
