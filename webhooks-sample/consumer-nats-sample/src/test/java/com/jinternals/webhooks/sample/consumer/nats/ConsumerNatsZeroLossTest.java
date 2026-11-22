package com.jinternals.webhooks.sample.consumer.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.standardwebhooks.Webhook;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.KeyValue;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "sample.nats.connect-on-startup=false",
            "sample.nats.worker-auto-start=false",
            "sample.nats.max-deliver=2",
            "sample.nats.retry-delay=1s"
        })
@AutoConfigureMockMvc
class ConsumerNatsZeroLossTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryNatsConsumerTestConfig {
        final Map<String, byte[]> kvStore = new ConcurrentHashMap<>();
        final List<byte[]> inboundPayloads = new ArrayList<>();
        final List<byte[]> dlqPayloads = new ArrayList<>();
        final AtomicBoolean failNextPublish = new AtomicBoolean(false);

        @Bean
        InMemoryNatsConsumerTestConfig natsState() {
            return this;
        }

        @Bean
        KeyValue idempotencyKeyValue() throws Exception {
            KeyValue kv = mock(KeyValue.class);
            when(kv.create(anyString(), any(byte[].class))).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                byte[] val = inv.getArgument(1);
                if (kvStore.putIfAbsent(key, val) != null) {
                    throw mock(JetStreamApiException.class);
                }
                return 1L;
            });
            when(kv.put(anyString(), any(byte[].class))).thenAnswer(inv -> {
                kvStore.put(inv.getArgument(0), inv.getArgument(1));
                return 2L;
            });
            doAnswer(inv -> {
                        kvStore.remove(inv.getArgument(0));
                        return null;
                    })
                    .when(kv)
                    .delete(anyString());
            return kv;
        }

        @Bean
        JetStream jetStream() throws Exception {
            JetStream js = mock(JetStream.class);
            PublishAck ack = mock(PublishAck.class);
            when(ack.getStream()).thenReturn("WEBHOOKS_INBOUND");
            when(ack.getSeqno()).thenReturn(1L);
            when(js.publish(eq("webhooks.inbound.events"), any(byte[].class), any(PublishOptions.class)))
                    .thenAnswer(inv -> {
                        if (failNextPublish.compareAndSet(true, false)) {
                            throw new IOException("NATS broker temporarily unavailable");
                        }
                        inboundPayloads.add(inv.getArgument(1));
                        return ack;
                    });
            when(js.publish(eq("webhooks.inbound.dlq.events"), any(byte[].class)))
                    .thenAnswer(inv -> {
                        dlqPayloads.add(inv.getArgument(1));
                        return ack;
                    });
            return js;
        }

        @Bean
        JetStreamSubscription inboundPullSubscription() {
            return mock(JetStreamSubscription.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NatsInboundWorker inboundWorker;

    @Autowired
    private ContactProjectionService projectionService;

    @Autowired
    private LoggingHandlerFailureListener failureListener;

    @Autowired
    private InMemoryNatsConsumerTestConfig natsState;

    private static Message mockJetStreamMessage(byte[] data, long deliveredCount) {
        Message msg = mock(Message.class);
        NatsJetStreamMetaData meta = mock(NatsJetStreamMetaData.class);
        when(meta.deliveredCount()).thenReturn(deliveredCount);
        when(msg.metaData()).thenReturn(meta);
        when(msg.getData()).thenReturn(data);
        return msg;
    }

    @Test
    void persistsToNatsBeforeReturning204DedupesRetriesAndRecoversWorkerFailure() throws Exception {
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();
        projectionService.clear();

        String msgId = "msg_zero_loss_1";
        long ts = Instant.now().getEpochSecond();
        String body =
                "{\"type\":\"contact.created\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"id\":\"c-10\",\"fullName\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}}";
        String sig = new Webhook(SECRET).sign(msgId, ts, body);

        // 1) First delivery: verified -> persisted to WEBHOOKS_INBOUND -> 204
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .header("x-trace-id", "trace-abc")
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(natsState.inboundPayloads).hasSize(1);

        // 2) Duplicate delivery of the same webhook-id: suppressed by NATS KV IdempotencyGuard (not re-queued)
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(natsState.inboundPayloads).hasSize(1);

        // 3) Downstream DB outage on attempt 1 -> nakWithDelay; attempt 2 -> processed & ackSync!
        byte[] stored = natsState.inboundPayloads.get(0);
        projectionService.failNextInvocation();
        Message attempt1 = mockJetStreamMessage(stored, 1L);
        inboundWorker.processOne(attempt1);
        verify(attempt1).nakWithDelay(Duration.ofSeconds(1));
        assertThat(projectionService.processed()).isEmpty();

        Message attempt2 = mockJetStreamMessage(stored, 2L);
        inboundWorker.processOne(attempt2);
        verify(attempt2).ackSync(Duration.ofSeconds(2));
        assertThat(projectionService.processed()).hasSize(1);
        assertThat(projectionService.processed().get(0).contact().getId()).isEqualTo("c-10");
    }

    @Test
    void releasesIdempotencyReservationAndReturns500WhenNatsPublishFailsSoSenderRetrySucceeds() throws Exception {
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();

        String msgId = "msg_nats_outage_1";
        long ts = Instant.now().getEpochSecond();
        String body =
                "{\"type\":\"contact.created\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"id\":\"c-11\",\"fullName\":\"Grace Hopper\",\"email\":\"grace@example.com\"}}";
        String sig = new Webhook(SECRET).sign(msgId, ts, body);

        // NATS fails during receive -> 500 Internal Server Error + NATS KV reservation released!
        natsState.failNextPublish.set(true);
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isInternalServerError());
        assertThat(natsState.inboundPayloads).isEmpty();

        // Sender retries the SAME webhook-id once NATS is back -> 204 and enqueued cleanly (0 webhook loss!)
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isNoContent());
        assertThat(natsState.inboundPayloads).hasSize(1);
    }

    @Test
    void routesExhaustedInboundMessageToDlqAndNotifiesFailureListener() throws Exception {
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();
        natsState.dlqPayloads.clear();
        failureListener.clear();

        String msgId = "msg_dlq_in_1";
        long ts = Instant.now().getEpochSecond();
        String body =
                "{\"type\":\"contact.created\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"id\":\"c-12\",\"fullName\":\"Alan Turing\",\"email\":\"alan@example.com\"}}";
        String sig = new Webhook(SECRET).sign(msgId, ts, body);

        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isNoContent());

        projectionService.failNextInvocation();
        Message finalAttempt = mockJetStreamMessage(natsState.inboundPayloads.get(0), 2L);
        inboundWorker.processOne(finalAttempt);

        verify(finalAttempt).term();
        assertThat(natsState.dlqPayloads).hasSize(1);
        assertThat(failureListener.failedTypes()).containsExactly("contact.created");
    }
}
