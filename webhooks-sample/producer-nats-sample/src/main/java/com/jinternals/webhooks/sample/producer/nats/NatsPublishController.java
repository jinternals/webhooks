package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.sample.producer.nats.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.nats.events.ContactCreated;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NatsPublishController {

    private final CrmWebhooks crmWebhooks;

    public NatsPublishController(CrmWebhooks crmWebhooks) {
        this.crmWebhooks = crmWebhooks;
    }

    @PostMapping("/publish/contact-created")
    public ResponseEntity<String> publish(@RequestBody ContactCreated event) {
        MDC.put(NatsJetStreamWebhookTransport.TRACE_MDC_KEY, UUID.randomUUID().toString());
        try {
            crmWebhooks.contactCreated(event);
            return ResponseEntity.accepted().body("enqueued");
        } finally {
            MDC.remove(NatsJetStreamWebhookTransport.TRACE_MDC_KEY);
        }
    }
}
