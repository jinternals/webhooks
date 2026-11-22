package com.jinternals.webhooks.sample.consumer.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
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
 * Multi-instance-safe NATS JetStream worker draining {@code WEBHOOKS_INBOUND}.
 *
 * <p>Each virtual-thread worker owns its own {@link JetStreamSubscription} bound to the shared durable pull
 * consumer ({@code webhooks-inbound-worker}) and fetches one message at a time ({@code fetch(1, 1s)}) so
 * messages are balanced cleanly across all running consumer pods without head-of-line {@code ackWait}
 * starvation.
 */
@Component
public class NatsInboundWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NatsInboundWorker.class);

    private final JetStream jetStream;
    private final ObjectMapper objectMapper;
    private final ContactProjectionService projectionService;
    private final HandlerFailureListener failureListener;
    private final NatsConsumerProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workers;

    public NatsInboundWorker(
            JetStream jetStream,
            ObjectMapper objectMapper,
            ContactProjectionService projectionService,
            HandlerFailureListener failureListener,
            NatsConsumerProperties properties) {
        this.jetStream = jetStream;
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
            workers = Executors.newVirtualThreadPerTaskExecutor();
            int concurrency = Math.max(1, properties.getWorkerConcurrency());
            for (int i = 0; i < concurrency; i++) {
                workers.submit(this::pollLoop);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && workers != null) {
            workers.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        try {
            JetStreamSubscription sub = jetStream.subscribe(
                    properties.getSubject(),
                    PullSubscribeOptions.bind(properties.getStream(), properties.getDurableConsumer()));
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                List<Message> batch = sub.fetch(1, Duration.ofSeconds(1));
                for (Message msg : batch) {
                    processOne(msg);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.warn("Inbound NATS worker exited: {}", e.getMessage());
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
