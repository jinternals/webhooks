package com.jinternals.webhooks.sample.producer.web;

import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooksV2a;
import com.jinternals.webhooks.sample.producer.events.ContactCreated;
import com.jinternals.webhooks.sample.producer.trace.TraceHeaderWebhookInterceptor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for the <strong>producer</strong> side. It exposes both signing schemes so they can be
 * compared directly:
 *
 * <ul>
 *   <li>{@code POST /publish/contact-created} — drives {@link CrmWebhooks}, which signs with the
 *       {@code demo} destination's {@code whsec_} secret ({@code v1}, symmetric HMAC) and POSTs to the
 *       consumer's {@code /webhooks/crm} endpoint;</li>
 *   <li>{@code POST /publish/contact-created-v2a} — drives {@link CrmWebhooksV2a}, which signs with the
 *       {@code demo-v2a} destination's {@code whsk_} Ed25519 private key ({@code v2a}, asymmetric;
 *       SPEC.md §19i) and POSTs to the consumer's {@code /webhooks/crm-v2a} endpoint.</li>
 * </ul>
 *
 * <p>Delivery has no retry/persistence: a failed send surfaces as {@link WebhookDeliveryException}, which
 * this controller maps to {@code 502 Bad Gateway} so the caller can decide whether to retry.
 *
 * <p><strong>Trace propagation (SPEC.md §9b).</strong> This is also the producer half of the end-to-end
 * trace example: it seeds a trace id into the SLF4J {@code MDC} for this unit of work, so both this app's log
 * lines and the outbound webhook carry it. {@link TraceHeaderWebhookInterceptor} copies that same id onto the
 * delivery ({@code x-trace-id}); the consumer sample reads it back, so one trace spans both services. The
 * toolkit itself never touches the {@code MDC} — binding the id here is the <em>application's</em> job (a real
 * app would typically let a Micrometer Tracing / OpenTelemetry bridge do it).
 */
@RestController
public class PublishController {

    private static final Logger log = LoggerFactory.getLogger(PublishController.class);

    private final CrmWebhooks crmWebhooks;
    private final CrmWebhooksV2a crmWebhooksV2a;

    public PublishController(CrmWebhooks crmWebhooks, CrmWebhooksV2a crmWebhooksV2a) {
        this.crmWebhooks = crmWebhooks;
        this.crmWebhooksV2a = crmWebhooksV2a;
    }

    /** Send a {@code contact.created} webhook signed with {@code v1} (symmetric HMAC). */
    @PostMapping("/publish/contact-created")
    public ResponseEntity<String> publishContactCreated(@RequestBody ContactCreated event) {
        return publish("v1", event, crmWebhooks::contactCreated);
    }

    /** Send a {@code contact.created} webhook signed with {@code v2a} (asymmetric Ed25519). */
    @PostMapping("/publish/contact-created-v2a")
    public ResponseEntity<String> publishContactCreatedV2a(@RequestBody ContactCreated event) {
        return publish("v2a", event, crmWebhooksV2a::contactCreated);
    }

    private ResponseEntity<String> publish(String scheme, ContactCreated event, ContactSender sender) {
        // Seed the trace id for this unit of work (a real app would let its tracing bridge do this). The
        // interceptor copies it onto the outbound webhook; the finally clears it so it never leaks.
        MDC.put(TraceHeaderWebhookInterceptor.TRACE_MDC_KEY, UUID.randomUUID().toString());
        try {
            sender.send(event);
            log.info("published contact.created ({}) for id={}", scheme, event.getId());
            return ResponseEntity.accepted().body("published");
        } catch (WebhookDeliveryException e) {
            // No retry here on purpose — the toolkit ships none. Report upstream and let the caller decide.
            log.warn("delivery of contact.created ({}) failed for id={}: {}", scheme, event.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("delivery failed: " + e.getMessage());
        } finally {
            MDC.remove(TraceHeaderWebhookInterceptor.TRACE_MDC_KEY);
        }
    }

    /** The shared shape of both clients' publish call, so one method serves the {@code v1} and {@code v2a} paths. */
    @FunctionalInterface
    private interface ContactSender {
        void send(ContactCreated event);
    }
}
