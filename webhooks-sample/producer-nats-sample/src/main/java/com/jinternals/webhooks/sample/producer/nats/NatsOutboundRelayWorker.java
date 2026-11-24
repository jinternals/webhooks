package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.sign.WebhookSigner;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.InterceptingWebhookTransport;
import com.jinternals.webhooks.producer.transport.RestClientWebhookTransport;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryListener;
import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import com.jinternals.webhooks.producer.transport.WebhookTransport;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Multi-instance-safe NATS JetStream relay worker draining {@code WEBHOOKS_OUTBOUND}.
 *
 * <ul>
 *   <li>All producer instances bind to the same durable pull consumer ({@code webhooks-outbound-relay});
 *       each virtual-thread worker fetches <strong>one message at a time</strong> ({@code fetch(1, 1s)})
 *       with {@code ackWait = 60s} ({@code > connectTimeout + readTimeout}) so slow destinations never cause
 *       head-of-line {@code ackWait} expiry across sibling pods.</li>
 *   <li>Every delivery attempt computes a <strong>fresh {@code webhook-timestamp} and
 *       {@code webhook-signature}</strong> over the unchanged {@code webhook-id} and {@code body}.</li>
 * </ul>
 */
@Component
public class NatsOutboundRelayWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NatsOutboundRelayWorker.class);

    private final JetStream jetStream;
    private final ObjectReader messageReader;
    private final WebhookProducerProperties producerProperties;
    private final Map<String, Destination> destinationsByUrl;
    private final NatsProducerProperties natsProperties;
    private final WebhookSigner signer;
    private final SecretProvider<Destination> secretProvider;
    private final WebhookDeliveryListener deliveryListener;
    private final WebhookTransport httpSender;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workers;

    public NatsOutboundRelayWorker(
            JetStream jetStream,
            ObjectMapper objectMapper,
            WebhookProducerProperties producerProperties,
            NatsProducerProperties natsProperties,
            WebhookSigner signer,
            @Qualifier("destinationSecretProvider") SecretProvider<Destination> secretProvider,
            DestinationUrlValidator urlValidator,
            WebhookDeliveryListener deliveryListener,
            List<WebhookRequestInterceptor> interceptors,
            @Qualifier("webhookProducerRestClient") RestClient restClient,
            ObjectProvider<Clock> clockProvider) {
        this.jetStream = jetStream;
        this.messageReader = objectMapper.readerFor(OutboundWebhookMessage.class);
        this.producerProperties = producerProperties;
        Map<String, Destination> indexed = new LinkedHashMap<>();
        for (Destination d : producerProperties.getDestinations().values()) {
            if (d != null && d.getUrl() != null) {
                indexed.putIfAbsent(d.getUrl(), d);
            }
        }
        this.destinationsByUrl = Map.copyOf(indexed);
        this.natsProperties = natsProperties;
        this.signer = signer;
        this.secretProvider = secretProvider;
        this.deliveryListener = deliveryListener;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        RestClientWebhookTransport base = new RestClientWebhookTransport(restClient, urlValidator, this.clock);
        this.httpSender = new InterceptingWebhookTransport(base, interceptors);
    }

    @Override
    public boolean isAutoStartup() {
        return natsProperties.isWorkerAutoStart();
    }

    @Override
    public int getPhase() {
        // Stop AFTER embedded Tomcat finishes draining inbound HTTP requests (DEFAULT_PHASE - 1024),
        // and BEFORE the NATS Connection bean is destroyed.
        return 0;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            workers = Executors.newVirtualThreadPerTaskExecutor();
            int concurrency = Math.max(1, natsProperties.getWorkerConcurrency());
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
        // Each worker thread owns its own JetStreamSubscription handle bound to the shared durable consumer
        // (io.nats.client.Subscription is not thread-safe across concurrent fetchers) and fetches 1 message
        // at a time so ackWait starts only when this thread is ready to send immediately.
        JetStreamSubscription sub = null;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    if (sub == null) {
                        sub = jetStream.subscribe(
                                natsProperties.getSubject(),
                                PullSubscribeOptions.bind(
                                        natsProperties.getStream(), natsProperties.getDurableConsumer()));
                    }
                    List<Message> batch = sub.fetch(1, Duration.ofSeconds(1));
                    for (Message msg : batch) {
                        deliverOne(msg);
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
                            "Transient error in outbound NATS relay loop; re-subscribing after backoff: {}",
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

    /** Deliver a single JetStream message: ack on 2xx, nakWithDelay on retryable failure, DLQ + term on abandon. */
    public void deliverOne(Message msg) {
        long attempt = msg.metaData() != null ? msg.metaData().deliveredCount() : 1L;
        OutboundWebhookMessage queued;
        try {
            queued = messageReader.readValue(msg.getData());
        } catch (Exception malformed) {
            log.error("Poison outbound message could not be parsed; routing to DLQ", malformed);
            String poisonKey = msg.metaData() != null
                    ? "poison_seq_" + msg.metaData().streamSequence()
                    : "poison_" + java.util.UUID.randomUUID();
            if (publishToDlq(msg.getData(), poisonKey)) {
                msg.term();
            } else {
                msg.nakWithDelay(natsProperties.getBaseRetryDelay());
            }
            return;
        }

        if (queued.traceId() != null && !queued.traceId().isBlank()) {
            MDC.put(NatsJetStreamWebhookTransport.TRACE_MDC_KEY, queued.traceId());
        }
        Map<String, List<String>> signedHeaders = Map.of();
        try {
            signedHeaders = buildAttemptHeaders(queued);
            httpSender.send(queued.url(), signedHeaders, queued.body());
        } catch (WebhookDeliveryException e) {
            handleDeliveryFailure(msg, queued, signedHeaders, attempt, e);
            return;
        } catch (RuntimeException signingOrConfigEx) {
            handleDeliveryFailure(
                    msg,
                    queued,
                    signedHeaders,
                    attempt,
                    new WebhookDeliveryException(
                            "Failed to prepare outbound webhook delivery: " + signingOrConfigEx.getMessage(),
                            signingOrConfigEx));
            return;
        } finally {
            MDC.remove(NatsJetStreamWebhookTransport.TRACE_MDC_KEY);
        }

        try {
            msg.ackSync(Duration.ofSeconds(2));
            if (log.isInfoEnabled()) {
                log.info(
                        "Delivered outbound webhook id={} attempt={} target={}",
                        queued.webhookId(),
                        attempt,
                        SafeUrl.sanitize(queued.url()));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acking outbound webhook id={}", queued.webhookId());
        } catch (Exception ackFailure) {
            log.warn("JetStream ack failed for id={}: {}", queued.webhookId(), ackFailure.getMessage());
        }
    }

    private Map<String, List<String>> buildAttemptHeaders(OutboundWebhookMessage queued) {
        long attemptSeconds = clock.instant().getEpochSecond();
        Map<String, List<String>> headers = new LinkedHashMap<>(4);
        headers.put(WebhookHeaders.ID, List.of(queued.webhookId()));
        headers.put(WebhookHeaders.TIMESTAMP, List.of(Long.toString(attemptSeconds)));
        if (signer.isEnabled()) {
            Destination destination = findDestinationByUrl(queued.url());
            List<String> secrets = secretProvider.secretsFor(destination);
            if (secrets == null || secrets.isEmpty()) {
                throw new WebhookDeliveryException(
                        "No signing secret configured for destination URL: " + SafeUrl.sanitize(queued.url()));
            }
            String combined = null;
            for (String secret : secrets) {
                if (secret == null || secret.isBlank()) {
                    throw new WebhookDeliveryException("Null/blank signing secret returned for destination URL: "
                            + SafeUrl.sanitize(queued.url()));
                }
                String sig = signer.sign(
                        secret, destination.getEndpointId(), queued.webhookId(), attemptSeconds, queued.body());
                combined = (combined == null) ? sig : combined + " " + sig;
            }
            headers.put(WebhookHeaders.SIGNATURE, List.of(combined));
        }
        return headers;
    }

    private Destination findDestinationByUrl(String url) {
        Destination indexed = url != null ? destinationsByUrl.get(url) : null;
        if (indexed != null) {
            return indexed;
        }
        return producerProperties.getDestinations().values().stream()
                .filter(d -> url != null && url.equals(d.getUrl()))
                .findFirst()
                .orElseThrow(() -> new WebhookDeliveryException(
                        "No configured webhook destination matches URL: " + SafeUrl.sanitize(url)));
    }

    private void handleDeliveryFailure(
            Message msg,
            OutboundWebhookMessage queued,
            Map<String, List<String>> signedHeaders,
            long attempt,
            WebhookDeliveryException e) {
        boolean retryable = isRetryable(e);
        if (retryable && attempt < natsProperties.getMaxDeliver()) {
            Duration delay = computeDelay(attempt, e.getRetryAfter().orElse(null));
            log.warn(
                    "Outbound webhook id={} failed (attempt {}/{}, status={}); scheduling NATS redelivery in {}ms",
                    queued.webhookId(),
                    attempt,
                    natsProperties.getMaxDeliver(),
                    e.getStatusCode().map(Object::toString).orElse("transport"),
                    delay.toMillis());
            msg.nakWithDelay(delay);
            return;
        }

        DeliveryFailure.Reason reason = retryable ? DeliveryFailure.Reason.EXHAUSTED : DeliveryFailure.Reason.FATAL;
        if (!publishToDlq(msg.getData(), queued.webhookId())) {
            // Never delete a message from WorkQueue if DLQ persistence itself failed — retry once NATS recovers.
            msg.nakWithDelay(natsProperties.getBaseRetryDelay());
            return;
        }
        try {
            deliveryListener.onDeliveryFailed(
                    new DeliveryFailure(queued.url(), signedHeaders, queued.body(), (int) attempt, reason, e));
        } catch (RuntimeException listenerEx) {
            log.error("WebhookDeliveryListener threw while handling dead-letter", listenerEx);
        }
        msg.term();
    }

    private boolean publishToDlq(byte[] rawPayload, String webhookId) {
        String dedupKey = (webhookId != null && !webhookId.isBlank())
                ? webhookId
                : java.util.UUID.randomUUID().toString();
        try {
            PublishOptions options = PublishOptions.builder()
                    .expectedStream(natsProperties.getDlqStream())
                    .messageId("dlq_" + dedupKey)
                    .build();
            jetStream.publish(natsProperties.getDlqSubject(), rawPayload, options);
            return true;
        } catch (Exception dlqEx) {
            log.error("Failed to publish abandoned webhook id={} to DLQ; retaining in WorkQueue", dedupKey, dlqEx);
            return false;
        }
    }

    private static boolean isRetryable(WebhookDeliveryException e) {
        return e.getStatusCode().map(code -> code == 429 || code >= 500).orElse(true);
    }

    private Duration computeDelay(long attempt, Duration retryAfter) {
        long multiplier = 1L << Math.clamp(attempt - 1L, 0L, 10L);
        Duration exp = natsProperties.getBaseRetryDelay().multipliedBy(multiplier);
        Duration effective = (retryAfter != null && retryAfter.compareTo(exp) > 0) ? retryAfter : exp;
        return effective.compareTo(natsProperties.getMaxRetryDelay()) > 0
                ? natsProperties.getMaxRetryDelay()
                : effective;
    }
}
