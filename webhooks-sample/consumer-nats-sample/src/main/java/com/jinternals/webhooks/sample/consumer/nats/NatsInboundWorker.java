package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

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
    private final ObjectReader inboundReader;
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
        this.inboundReader = objectMapper.readerFor(InboundWebhookMessage.class);
        this.projectionService = projectionService;
        this.failureListener = failureListener;
        this.properties = properties;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isWorkerAutoStart();
    }

    @Override
    public int getPhase() {
        // Stop AFTER embedded Tomcat drains in-flight HTTP webhook deliveries, and BEFORE NATS Connection closes.
        return 0;
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
            workers.shutdown();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException ie) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        JetStreamSubscription sub = null;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    if (sub == null) {
                        sub = jetStream.subscribe(
                                properties.getSubject(),
                                PullSubscribeOptions.bind(properties.getStream(), properties.getDurableConsumer()));
                    }
                    List<Message> batch = sub.fetch(1, Duration.ofSeconds(1));
                    for (Message msg : batch) {
                        processOne(msg);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    log.warn(
                            "Transient error in inbound NATS worker loop; re-subscribing after backoff: {}",
                            e.getMessage());
                    safeUnsubscribe(sub);
                    sub = null;
                    if (!sleepInterruptibly(Duration.ofSeconds(1))) {
                        break;
                    }
                }
            }
        } finally {
            safeUnsubscribe(sub);
        }
    }

    private static void safeUnsubscribe(JetStreamSubscription sub) {
        if (sub != null) {
            try {
                sub.unsubscribe();
            } catch (Exception ignored) {
                // Best-effort cleanup during reconnect or shutdown
            }
        }
    }

    private static boolean sleepInterruptibly(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void processOne(Message msg) {
        long attempt = msg.metaData() != null ? msg.metaData().deliveredCount() : 1L;
        InboundWebhookMessage inbound;
        try {
            inbound = inboundReader.readValue(msg.getData());
        } catch (Exception malformed) {
            log.error("Poison inbound message could not be parsed; routing to DLQ", malformed);
            String poisonKey = msg.metaData() != null
                    ? "poison_seq_" + msg.metaData().streamSequence()
                    : "poison_" + java.util.UUID.randomUUID();
            if (publishToDlq(msg.getData(), poisonKey)) {
                msg.term();
            } else {
                msg.nakWithDelay(properties.getRetryDelay());
            }
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
        } catch (Exception e) {
            handleProcessingFailure(msg, inbound, attempt, e);
            return;
        } finally {
            MDC.remove(TraceContextFilter.WEBHOOK_ID_MDC_KEY);
            MDC.remove(TraceContextFilter.TRACE_MDC_KEY);
        }

        try {
            msg.ackSync(Duration.ofSeconds(2));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acking inbound webhook id={}", inbound.webhookId());
        } catch (Exception ackEx) {
            log.warn(
                    "JetStream ack failed after processing id={} (idempotent on redelivery): {}",
                    inbound.webhookId(),
                    ackEx.getMessage());
        }
    }

    private void handleProcessingFailure(Message msg, InboundWebhookMessage inbound, long attempt, Exception e) {
        if (attempt < properties.getMaxDeliver()) {
            log.warn(
                    "Inbound webhook id={} processing failed (attempt {}/{}); nakWithDelay({})",
                    inbound.webhookId(),
                    attempt,
                    properties.getMaxDeliver(),
                    properties.getRetryDelay());
            msg.nakWithDelay(properties.getRetryDelay());
            return;
        }
        log.error("Inbound webhook id={} exhausted {} attempts; routing to DLQ", inbound.webhookId(), attempt, e);
        if (!publishToDlq(msg.getData(), inbound.webhookId())) {
            // Never delete a message from WorkQueue if DLQ persistence itself failed.
            msg.nakWithDelay(properties.getRetryDelay());
            return;
        }
        try {
            failureListener.onHandlerFailed(inbound.type(), e);
        } catch (RuntimeException listenerEx) {
            log.error("HandlerFailureListener threw while handling dead-letter", listenerEx);
        }
        msg.term();
    }

    private boolean publishToDlq(byte[] rawPayload, String webhookId) {
        String dedupKey = (webhookId != null && !webhookId.isBlank())
                ? webhookId
                : java.util.UUID.randomUUID().toString();
        try {
            PublishOptions options = PublishOptions.builder()
                    .expectedStream(properties.getDlqStream())
                    .messageId("dlq_" + dedupKey)
                    .build();
            jetStream.publish(properties.getDlqSubject(), rawPayload, options);
            return true;
        } catch (Exception dlqEx) {
            log.error("Failed to publish inbound webhook id={} to DLQ; retaining in WorkQueue", dedupKey, dlqEx);
            return false;
        }
    }
}
