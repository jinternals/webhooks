package com.jinternals.webhooks.consumer.web;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataFactory;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.verify.WebhookVerificationException;
import com.jinternals.webhooks.consumer.web.error.InvalidPayloadException;
import com.jinternals.webhooks.consumer.web.receive.EventResolver;
import com.jinternals.webhooks.consumer.web.receive.ReceiveObservabilityRecorder;
import com.jinternals.webhooks.consumer.web.receive.Resolution;
import com.jinternals.webhooks.consumer.web.receive.TimedHandlerDispatcher;
import com.jinternals.webhooks.consumer.web.receive.WebhookBodyVerifier;
import com.jinternals.webhooks.consumer.web.receive.WebhookRequestHeaders;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * Orchestrates the receive pipeline for a single delivery (see SPEC.md §10): verify → dedupe → resolve
 * (route + deserialize + validate) → metadata → observability → dispatch. Each step is delegated to a
 * cohesive single-responsibility collaborator
 * ({@link WebhookBodyVerifier}, {@link EventResolver}, {@link ReceiveObservabilityRecorder},
 * {@link TimedHandlerDispatcher}); this class only sequences them and owns the dedupe short-circuit and
 * envelope parse.
 */
public class WebhookReceivePipeline {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceivePipeline.class);

    private final WebhookBodyVerifier bodyVerifier;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper objectMapper;
    private final EventResolver eventResolver;
    private final MetadataFactory metadataFactory;
    private final ReceiveObservabilityRecorder observability;
    private final TimedHandlerDispatcher eventDispatcher;

    public WebhookReceivePipeline(
            WebhookBodyVerifier bodyVerifier,
            IdempotencyGuard idempotency,
            ObjectMapper objectMapper,
            EventResolver eventResolver,
            MetadataFactory metadataFactory,
            ReceiveObservabilityRecorder observability,
            TimedHandlerDispatcher eventDispatcher) {
        this.bodyVerifier = bodyVerifier;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.eventResolver = eventResolver;
        this.metadataFactory = metadataFactory;
        this.observability = observability;
        this.eventDispatcher = eventDispatcher;
    }

    /**
     * Run the receive pipeline for a delivery. Returns
     * {@code 204} on acceptance (sync: also processed); throws on verification / unknown-type /
     * payload-validation failures (surfaced by
     * {@link com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler}).
     */
    public ResponseEntity<Void> process(
            HttpServletRequest request, Endpoint endpoint, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException {
        log.info("Webhook received, verifying");

        // 1) Verify the RAW body first (never re-serialize before verifying).
        bodyVerifier.verify(endpoint, rawBody, headers);

        // 2) Optional best-effort dedupe: reserve the id; a reservation that is still live means a
        // concurrent or already-processed duplicate, which we skip. The reservation is committed only
        // after successful processing (or released on failure), so a failed delivery is retryable.
        String webhookId = WebhookRequestHeaders.first(headers, WebhookHeaders.ID);
        if (!idempotency.tryBegin(webhookId)) {
            log.info("Duplicate webhook-id {} ignored", webhookId);
            return ResponseEntity.noContent().build();
        }
        try {
            return processReserved(request, endpoint, rawBody, headers, webhookId);
        } catch (RuntimeException e) {
            // Processing failed: drop the reservation so the sender's retry of this id is allowed to run.
            idempotency.release(webhookId);
            throw e;
        }
    }

    private ResponseEntity<Void> processReserved(
            HttpServletRequest request,
            Endpoint endpoint,
            String rawBody,
            Map<String, List<String>> headers,
            String webhookId) {
        // 3) Parse the envelope, then route + (strict) deserialize + validate the event. A malformed body
        // is a sender 400; an unaccepted or unmodelled type resolves to Ignored under ack-and-ignore
        // (→ 204) or throws under reject (→ 400).
        Envelope envelope = parse(rawBody);
        switch (eventResolver.resolve(endpoint, envelope)) {
            case Resolution.Ignored ignored -> {
                // Nothing to process, but the id was reserved: commit it so a retry is still deduped.
                idempotency.markProcessed(webhookId);
                return ResponseEntity.noContent().build();
            }
            case Resolution.Accepted accepted -> {
                dispatch(request, endpoint, envelope, headers, accepted);
                // Reached only when dispatch returned normally: sync → the handler succeeded; async → the
                // delivery was accepted. Either way the id is now safely committed.
                idempotency.markProcessed(webhookId);
                return ResponseEntity.noContent().build(); // 204 => accepted (sync: also processed)
            }
        }
    }

    private void dispatch(
            HttpServletRequest request,
            Endpoint endpoint,
            Envelope envelope,
            Map<String, List<String>> headers,
            Resolution.Accepted accepted) {
        String type = accepted.type();
        Object event = accepted.event();
        WebhookMetadata metadata = metadataFactory.build(request, envelope, event, headers, endpoint.getPath());
        observability.record(type, metadata);
        eventDispatcher.dispatch(type, event, metadata);
    }

    /** Parse the verified raw body into an {@link Envelope}, mapping a malformed body to a 400. */
    private Envelope parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, Envelope.class);
        } catch (JacksonException e) {
            throw new InvalidPayloadException(e.getOriginalMessage(), e);
        }
    }
}
