package com.jinternals.webhooks.sample.consumer.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Durable NATS JetStream worker that drains {@code WEBHOOKS_INBOUND} and executes downstream business logic
 * with explicit {@code ackSync} / {@code nakWithDelay} and poison-message routing to
 * {@code WEBHOOKS_INBOUND_DLQ}.
 */
@Component
public class NatsInboundWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NatsInboundWorker.class);

    private final JetStream jetStream;
    private final JetStreamSubscription subscription;
    private final ObjectMapper objectMapper;
    private final ContactProjectionService projectionService;
    private final HandlerFailureListener failureListener;
    private final NatsConsumerProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService poller;

    public NatsInboundWorker(
            JetStream jetStream,
            JetStreamSubscription subscription,
            ObjectMapper objectMapper,
            ContactProjectionService projectionService,
            HandlerFailureListener failureListener,
            NatsConsumerProperties properties) {
        this.jetStream = jetStream;
        this.subscription = subscription;
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
        this.failureListener = failureListener;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isWorkerAutoStart();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            poller = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nats-inbound-worker");
                t.setDaemon(true);
                return t;
            });
            poller.submit(this::pollLoop);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && poller != null) {
            poller.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<Message> batch = subscription.fetch(10, Duration.ofSeconds(1));
                for (Message msg : batch) {
                    processOne(msg);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Inbound NATS poll iteration failed: {}", e.getMessage());
                }
            }
        }
    }

    public void processOne(Message msg) {
        long attempt = msg.metaData() != null ? msg.metaData().deliveredCount() : 1L;
        InboundWebhookMessage inbound;
        try {
            inbound = objectMapper.readValue(msg.getData(), InboundWebhookMessage.class);
        } catch (Exception malformed) {
            log.error("Poison inbound message could not be parsed; terminating", malformed);
            msg.term();
            return;
        }

        if (inbound.webhookId() != null) {
            MDC.put(TraceContextFilter.WEBHOOK_ID_MDC_KEY, inbound.webhookId());
        }
        if (inbound.traceId() != null) {
            MDC.put(TraceContextFilter.TRACE_MDC_KEY, inbound.traceId());
        }
        try {
            projectionService.process(inbound);
            msg.ackSync(Duration.ofSeconds(2));
        } catch (Exception e) {
            if (attempt < properties.getMaxDeliver()) {
                log.warn(
                        "Inbound webhook id={} processing failed (attempt {}/{}); nakWithDelay({})",
                        inbound.webhookId(),
                        attempt,
                        properties.getMaxDeliver(),
                        properties.getRetryDelay());
                msg.nakWithDelay(properties.getRetryDelay());
            } else {
                log.error(
                        "Inbound webhook id={} exhausted {} attempts; routing to DLQ", inbound.webhookId(), attempt, e);
                publishToDlq(msg.getData(), inbound.webhookId());
                failureListener.onHandlerFailed(inbound.type(), e);
                msg.term();
            }
        } finally {
            MDC.remove(TraceContextFilter.WEBHOOK_ID_MDC_KEY);
            MDC.remove(TraceContextFilter.TRACE_MDC_KEY);
        }
    }

    private void publishToDlq(byte[] rawPayload, String webhookId) {
        try {
            jetStream.publish(properties.getDlqSubject(), rawPayload);
        } catch (Exception dlqEx) {
            log.error("Failed to publish inbound webhook id={} to DLQ", webhookId, dlqEx);
        }
    }
}
