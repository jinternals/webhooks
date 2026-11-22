package com.jinternals.webhooks.sample.producer.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Durable NATS JetStream relay worker that drains {@code WEBHOOKS_OUTBOUND} and delivers each webhook over
 * HTTP.
 *
 * <p><strong>Standard Webhooks replay-window safety:</strong> every time an attempt is made — even hours
 * after the event was originally queued — this worker computes a <em>fresh</em> {@code webhook-timestamp}
 * and {@code webhook-signature} over the unchanged {@code webhook-id} and serialized envelope {@code body},
 * so the consumer's 5-minute replay window never rejects a legitimate delayed retry.
 */
@Component
public class NatsOutboundRelayWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NatsOutboundRelayWorker.class);

    private final JetStream jetStream;
    private final JetStreamSubscription subscription;
    private final ObjectMapper objectMapper;
    private final WebhookProducerProperties producerProperties;
    private final NatsProducerProperties natsProperties;
    private final WebhookSigner signer;
    private final SecretProvider<Destination> secretProvider;
    private final WebhookDeliveryListener deliveryListener;
    private final WebhookTransport httpSender;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService poller;

    public NatsOutboundRelayWorker(
            JetStream jetStream,
            JetStreamSubscription subscription,
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
        this.subscription = subscription;
        this.objectMapper = objectMapper;
        this.producerProperties = producerProperties;
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
    public void start() {
        if (running.compareAndSet(false, true)) {
            poller = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nats-outbound-relay");
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
                    deliverOne(msg);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Outbound NATS poll iteration failed: {}", e.getMessage());
                }
            }
        }
    }

    /** Deliver a single JetStream message: ack on 2xx, nakWithDelay on retryable failure, DLQ + term on abandon. */
    public void deliverOne(Message msg) {
        long attempt = msg.metaData() != null ? msg.metaData().deliveredCount() : 1L;
        OutboundWebhookMessage queued;
        try {
            queued = objectMapper.readValue(msg.getData(), OutboundWebhookMessage.class);
        } catch (Exception malformed) {
            log.error("Poison outbound message could not be parsed; terminating", malformed);
            msg.term();
            return;
        }

        Map<String, List<String>> signedHeaders = buildAttemptHeaders(queued);
        if (queued.traceId() != null && !queued.traceId().isBlank()) {
            MDC.put(NatsJetStreamWebhookTransport.TRACE_MDC_KEY, queued.traceId());
        }
        try {
            httpSender.send(queued.url(), signedHeaders, queued.body());
            msg.ackSync(Duration.ofSeconds(2));
            log.info(
                    "Delivered outbound webhook id={} attempt={} target={}",
                    queued.webhookId(),
                    attempt,
                    SafeUrl.sanitize(queued.url()));
        } catch (WebhookDeliveryException e) {
            handleDeliveryFailure(msg, queued, signedHeaders, attempt, e);
        } catch (Exception ackFailure) {
            log.warn("JetStream ack failed for id={}: {}", queued.webhookId(), ackFailure.getMessage());
        } finally {
            MDC.remove(NatsJetStreamWebhookTransport.TRACE_MDC_KEY);
        }
    }

    private Map<String, List<String>> buildAttemptHeaders(OutboundWebhookMessage queued) {
        long attemptSeconds = clock.instant().getEpochSecond();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(WebhookHeaders.ID, List.of(queued.webhookId()));
        headers.put(WebhookHeaders.TIMESTAMP, List.of(Long.toString(attemptSeconds)));
        if (signer.isEnabled()) {
            Destination destination = findDestinationByUrl(queued.url());
            List<String> secrets = secretProvider.secretsFor(destination);
            StringBuilder combined = new StringBuilder();
            for (String secret : secrets) {
                if (combined.length() > 0) {
                    combined.append(' ');
                }
                combined.append(signer.sign(
                        secret, destination.getEndpointId(), queued.webhookId(), attemptSeconds, queued.body()));
            }
            headers.put(WebhookHeaders.SIGNATURE, List.of(combined.toString()));
        }
        return headers;
    }

    private Destination findDestinationByUrl(String url) {
        return producerProperties.getDestinations().values().stream()
                .filter(d -> url.equals(d.getUrl()))
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
        publishToDlq(msg.getData(), queued.webhookId());
        try {
            deliveryListener.onDeliveryFailed(
                    new DeliveryFailure(queued.url(), signedHeaders, queued.body(), (int) attempt, reason, e));
        } catch (RuntimeException listenerEx) {
            log.error("WebhookDeliveryListener threw while handling dead-letter", listenerEx);
        }
        msg.term();
    }

    private void publishToDlq(byte[] rawPayload, String webhookId) {
        try {
            jetStream.publish(natsProperties.getDlqSubject(), rawPayload);
        } catch (Exception dlqEx) {
            log.error("Failed to publish abandoned webhook id={} to DLQ", webhookId, dlqEx);
        }
    }

    private static boolean isRetryable(WebhookDeliveryException e) {
        return e.getStatusCode().map(code -> code == 429 || code >= 500).orElse(true);
    }

    private Duration computeDelay(long attempt, Duration retryAfter) {
        long multiplier = 1L << Math.min(attempt - 1L, 10L);
        Duration exp = natsProperties.getBaseRetryDelay().multipliedBy(multiplier);
        Duration effective = (retryAfter != null && retryAfter.compareTo(exp) > 0) ? retryAfter : exp;
        return effective.compareTo(natsProperties.getMaxRetryDelay()) > 0
                ? natsProperties.getMaxRetryDelay()
                : effective;
    }
}
