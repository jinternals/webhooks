package com.jinternals.webhooks.sample.consumer.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import com.standardwebhooks.Webhook;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "sample.nats.connect-on-startup=false",
            "sample.nats.worker-auto-start=false",
            "sample.nats.in-flight-lease=30s",
            "sample.nats.max-deliver=2",
            "sample.nats.retry-delay=1s"
        })
@AutoConfigureMockMvc
class ConsumerNatsZeroLossTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final AtomicLong epochSeconds = new AtomicLong(Instant.now().getEpochSecond());

    record StoredKvEntry(String value, Instant created, long revision) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryNatsConsumerTestConfig {
        final Map<String, StoredKvEntry> kvStore = new ConcurrentHashMap<>();
        final AtomicLong revisionSeq = new AtomicLong(1L);
        final List<byte[]> inboundPayloads = new ArrayList<>();
        final List<byte[]> dlqPayloads = new ArrayList<>();
        final List<String> dlqMessageIds = new ArrayList<>();
        final AtomicBoolean failNextPublish = new AtomicBoolean(false);
        final AtomicBoolean failNextKvPut = new AtomicBoolean(false);

        @Bean
        InMemoryNatsConsumerTestConfig natsState() {
            return this;
        }

        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return Instant.ofEpochSecond(epochSeconds.get());
                }
            };
        }

        @Bean
        KeyValue idempotencyKeyValue() throws Exception {
            KeyValue kv = mock(KeyValue.class);
            when(kv.create(anyString(), any(byte[].class))).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = new String((byte[]) inv.getArgument(1), StandardCharsets.UTF_8);
                long rev = revisionSeq.getAndIncrement();
                if (kvStore.putIfAbsent(key, new StoredKvEntry(val, Instant.ofEpochSecond(epochSeconds.get()), rev))
                        != null) {
                    throw wrongLastSequence();
                }
                return rev;
            });
            when(kv.get(anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                StoredKvEntry stored = kvStore.get(key);
                if (stored == null) {
                    return null;
                }
                KeyValueEntry entry = mock(KeyValueEntry.class);
                when(entry.getValueAsString()).thenReturn(stored.value());
                when(entry.getCreated()).thenReturn(ZonedDateTime.ofInstant(stored.created(), ZoneOffset.UTC));
                when(entry.getRevision()).thenReturn(stored.revision());
                return entry;
            });
            when(kv.update(anyString(), any(byte[].class), anyLong())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = new String((byte[]) inv.getArgument(1), StandardCharsets.UTF_8);
                long expectedRev = inv.getArgument(2);
                long nextRev = revisionSeq.getAndIncrement();
                boolean[] updated = {false};
                kvStore.computeIfPresent(key, (k, existing) -> {
                    if (existing.revision() == expectedRev) {
                        updated[0] = true;
                        return new StoredKvEntry(val, Instant.ofEpochSecond(epochSeconds.get()), nextRev);
                    }
                    return existing;
                });
                if (!updated[0]) {
                    throw wrongLastSequence();
                }
                return nextRev;
            });
            when(kv.put(anyString(), any(byte[].class))).thenAnswer(inv -> {
                if (failNextKvPut.compareAndSet(true, false)) {
                    throw new IOException("Transient NATS KV write failure");
                }
                String key = inv.getArgument(0);
                String val = new String((byte[]) inv.getArgument(1), StandardCharsets.UTF_8);
                long rev = revisionSeq.getAndIncrement();
                kvStore.put(key, new StoredKvEntry(val, Instant.ofEpochSecond(epochSeconds.get()), rev));
                return rev;
            });
            doAnswer(inv -> {
                        kvStore.remove(inv.getArgument(0));
                        return null;
                    })
                    .when(kv)
                    .delete(anyString());
            doAnswer(inv -> {
                        String key = inv.getArgument(0);
                        long expectedRev = inv.getArgument(1);
                        boolean[] deleted = {false};
                        kvStore.computeIfPresent(key, (k, existing) -> {
                            if (existing.revision() == expectedRev) {
                                deleted[0] = true;
                                return null;
                            }
                            return existing;
                        });
                        if (!deleted[0] && kvStore.containsKey(key)) {
                            throw wrongLastSequence();
                        }
                        return null;
                    })
                    .when(kv)
                    .delete(anyString(), anyLong());
            return kv;
        }

        private static JetStreamApiException wrongLastSequence() {
            JetStreamApiException ex = mock(JetStreamApiException.class);
            when(ex.getApiErrorCode()).thenReturn(NatsKvIdempotencyGuard.JS_WRONG_LAST_SEQUENCE);
            return ex;
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
            when(js.publish(eq("webhooks.inbound.dlq.events"), any(byte[].class), any(PublishOptions.class)))
                    .thenAnswer(inv -> {
                        PublishOptions opts = inv.getArgument(2);
                        dlqMessageIds.add(opts.getMessageId());
                        dlqPayloads.add(inv.getArgument(1));
                        return ack;
                    });
            return js;
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
    private NatsKvIdempotencyGuard idempotencyGuard;

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
    void persistsToNatsBeforeReturning204DedupesRetriesAndRecoversWorkerFailureIdempotently() throws Exception {
        epochSeconds.set(Instant.now().getEpochSecond());
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();
        projectionService.clear();

        String msgId = "msg_zero_loss_1";
        long ts = epochSeconds.get();
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

        // 2) Committed "processed" entry suppresses duplicate retry across any consumer instance -> 204
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isNoContent());
        assertThat(natsState.inboundPayloads).hasSize(1);

        // 3) Worker attempt 1 fails -> nakWithDelay; attempt 2 succeeds -> ackSync; duplicate redelivery is idempotent!
        byte[] stored = natsState.inboundPayloads.get(0);
        projectionService.failNextInvocation();
        Message attempt1 = mockJetStreamMessage(stored, 1L);
        inboundWorker.processOne(attempt1);
        verify(attempt1).nakWithDelay(Duration.ofSeconds(1));

        Message attempt2 = mockJetStreamMessage(stored, 2L);
        inboundWorker.processOne(attempt2);
        verify(attempt2).ackSync(Duration.ofSeconds(2));

        Message crashBeforeAckRedelivery = mockJetStreamMessage(stored, 3L);
        inboundWorker.processOne(crashBeforeAckRedelivery);
        verify(crashBeforeAckRedelivery).ackSync(Duration.ofSeconds(2));
        assertThat(projectionService.processed()).hasSize(1);
    }

    @Test
    void multiConsumerInFlightContentionReturns500WhileActiveAndReclaimsExpiredCrashedPodLease() throws Exception {
        epochSeconds.set(Instant.now().getEpochSecond());
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();

        String msgId = "msg_crash_recovery_1";
        String key = NatsKvIdempotencyGuard.encodeKey(msgId);
        // Simulate Consumer Pod #1 reserving "in-flight" at t=T0 and then pausing / crashing before PublishAck
        natsState.kvStore.put(key, new StoredKvEntry("in-flight", Instant.ofEpochSecond(epochSeconds.get()), 1L));

        long ts = epochSeconds.get();
        String body =
                "{\"type\":\"contact.created\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"id\":\"c-20\",\"fullName\":\"Barbara Liskov\",\"email\":\"barbara@example.com\"}}";
        String sig = new Webhook(SECRET).sign(msgId, ts, body);

        // 1) At T0 + 5s (lease still active): Consumer Pod #2 returns 500 (retryable!) WITHOUT deleting Pod #1's lock
        epochSeconds.addAndGet(5L);
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isInternalServerError());
        assertThat(natsState.kvStore.get(key).value()).isEqualTo("in-flight");
        assertThat(natsState.inboundPayloads).isEmpty();

        // 2) At T0 + 35s (> 30s lease — Pod #1 died via kill -9!): Pod #2 reclaims lease via CAS update & persists!
        epochSeconds.addAndGet(30L);
        long freshTs = epochSeconds.get();
        String freshSig = new Webhook(SECRET).sign(msgId, freshTs, body);
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(freshTs))
                        .header(WebhookHeaders.SIGNATURE, freshSig)
                        .content(body))
                .andExpect(status().isNoContent());
        assertThat(natsState.inboundPayloads).hasSize(1);
        assertThat(natsState.kvStore.get(key).value()).isEqualTo("processed");
    }

    @Test
    void releasesIdempotencyReservationAndReturns500WhenNatsPublishFailsSoSenderRetrySucceeds() throws Exception {
        epochSeconds.set(Instant.now().getEpochSecond());
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();

        String msgId = "msg_nats_outage_1";
        long ts = epochSeconds.get();
        String body =
                "{\"type\":\"contact.created\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"data\":{\"id\":\"c-11\",\"fullName\":\"Grace Hopper\",\"email\":\"grace@example.com\"}}";
        String sig = new Webhook(SECRET).sign(msgId, ts, body);

        natsState.failNextPublish.set(true);
        mockMvc.perform(post("/webhooks/crm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookHeaders.ID, msgId)
                        .header(WebhookHeaders.TIMESTAMP, Long.toString(ts))
                        .header(WebhookHeaders.SIGNATURE, sig)
                        .content(body))
                .andExpect(status().isInternalServerError());
        assertThat(natsState.inboundPayloads).isEmpty();

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
        epochSeconds.set(Instant.now().getEpochSecond());
        natsState.kvStore.clear();
        natsState.inboundPayloads.clear();
        natsState.dlqPayloads.clear();
        failureListener.clear();

        String msgId = "msg_dlq_in_1";
        long ts = epochSeconds.get();
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

    @Test
    void stalePodReleaseDoesNotWipeSiblingProcessedStateAndAckFailureDoesNotFalseDlq() throws Exception {
        epochSeconds.set(Instant.now().getEpochSecond());
        natsState.kvStore.clear();
        natsState.dlqPayloads.clear();
        failureListener.clear();
        projectionService.clear();

        // 1) Pod 1 reserves "msg_stale_pod_1" -> stalls -> Pod 2 reclaims & marks "processed" -> Pod 1 calls release()
        String msgId = "msg_stale_pod_1";
        String key = NatsKvIdempotencyGuard.encodeKey(msgId);
        assertThat(idempotencyGuard.tryBegin(msgId)).isTrue();
        // Simulate Pod 2 updating the KV entry to "processed" with a newer revision while Pod 1 was stalled
        natsState.kvStore.put(key, new StoredKvEntry("processed", Instant.ofEpochSecond(epochSeconds.get()), 999L));
        idempotencyGuard.release(msgId);
        // Revision-checked delete MUST NOT wipe Pod 2's "processed" state!
        assertThat(natsState.kvStore.get(key).value()).isEqualTo("processed");

        // 2) If projectionService.process() succeeds on final attempt (2/2) and ackSync times out, DO NOT false-DLQ!
        byte[] payload =
                "{\"webhookId\":\"msg_ack_timeout\",\"type\":\"contact.created\",\"endpointPath\":\"/webhooks/crm\",\"traceId\":null,\"contact\":{\"id\":\"c-ack\",\"fullName\":\"Donald Knuth\",\"email\":\"knuth@example.com\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        Message ackTimeoutMsg = mockJetStreamMessage(payload, 2L);
        doThrow(new java.util.concurrent.TimeoutException("ack timed out"))
                .when(ackTimeoutMsg)
                .ackSync(any());
        inboundWorker.processOne(ackTimeoutMsg);
        verify(ackTimeoutMsg, never()).term();
        assertThat(natsState.dlqPayloads).isEmpty();
        assertThat(failureListener.failedTypes()).isEmpty();
        assertThat(projectionService.processed()).hasSize(1);

        // 3) If markProcessed() fails, ownedRevision is retained so release() still performs CAS delete!
        String markFailId = "msg_mark_fail_1";
        String markFailKey = NatsKvIdempotencyGuard.encodeKey(markFailId);
        assertThat(idempotencyGuard.tryBegin(markFailId)).isTrue();
        natsState.failNextKvPut.set(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> idempotencyGuard.markProcessed(markFailId))
                .isInstanceOf(IllegalStateException.class);
        // Sibling pod reclaims & marks processed at rev 888 before our release() runs:
        natsState.kvStore.put(
                markFailKey, new StoredKvEntry("processed", Instant.ofEpochSecond(epochSeconds.get()), 888L));
        idempotencyGuard.release(markFailId);
        assertThat(natsState.kvStore.get(markFailKey).value()).isEqualTo("processed");

        // 4) Distinct poison messages carry distinct streamSequence-keyed DLQ messageIds (never deduplicated!)
        natsState.dlqMessageIds.clear();
        Message poison1 = mockJetStreamMessage("{not-json-1".getBytes(StandardCharsets.UTF_8), 1L);
        when(poison1.metaData().streamSequence()).thenReturn(101L);
        Message poison2 = mockJetStreamMessage("{not-json-2".getBytes(StandardCharsets.UTF_8), 1L);
        when(poison2.metaData().streamSequence()).thenReturn(102L);
        inboundWorker.processOne(poison1);
        inboundWorker.processOne(poison2);
        assertThat(natsState.dlqMessageIds).containsExactly("dlq_poison_seq_101", "dlq_poison_seq_102");
    }
}
