package com.jinternals.webhooks.sample.producer.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.sample.producer.nats.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.nats.events.ContactCreated;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "sample.nats.connect-on-startup=false",
            "sample.nats.worker-auto-start=false",
            "sample.nats.max-deliver=2",
            "sample.nats.base-retry-delay=500ms",
            "sample.nats.max-retry-delay=5s"
        })
class ProducerNatsZeroLossTest {

    private static HttpServer server;
    private static final AtomicInteger nextStatus = new AtomicInteger(204);
    private static final AtomicInteger retryAfterSeconds = new AtomicInteger(0);
    private static final List<Map<String, List<String>>> deliveredHeaders = new CopyOnWriteArrayList<>();
    private static final List<String> deliveredBodies = new CopyOnWriteArrayList<>();
    private static final AtomicLong epochSeconds = new AtomicLong(1_700_000_000L);

    @BeforeAll
    static void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks/crm", exchange -> {
            deliveredHeaders.add(exchange.getRequestHeaders());
            deliveredBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int ra = retryAfterSeconds.get();
            if (ra > 0) {
                exchange.getResponseHeaders().add("Retry-After", Integer.toString(ra));
            }
            exchange.sendResponseHeaders(nextStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopReceiver() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "webhooks.producer.destinations.crm.url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/crm");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryNatsTestConfig {
        final List<byte[]> outboundPayloads = new ArrayList<>();
        final List<byte[]> dlqPayloads = new ArrayList<>();

        @Bean
        InMemoryNatsTestConfig natsState() {
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
        JetStream jetStream() throws Exception {
            JetStream js = mock(JetStream.class);
            PublishAck ack = mock(PublishAck.class);
            when(ack.getStream()).thenReturn("WEBHOOKS_OUTBOUND");
            when(ack.getSeqno()).thenReturn(1L);
            when(js.publish(eq("webhooks.outbound.send"), any(byte[].class), any(PublishOptions.class)))
                    .thenAnswer(inv -> {
                        outboundPayloads.add(inv.getArgument(1));
                        return ack;
                    });
            when(js.publish(eq("webhooks.outbound.dlq.send"), any(byte[].class)))
                    .thenAnswer(inv -> {
                        dlqPayloads.add(inv.getArgument(1));
                        return ack;
                    });
            return js;
        }
    }

    @Autowired
    private CrmWebhooks crmWebhooks;

    @Autowired
    private NatsOutboundRelayWorker relayWorker;

    @Autowired
    private DeadLetterDeliveryListener deadLetterListener;

    @Autowired
    private InMemoryNatsTestConfig natsState;

    private static Message mockJetStreamMessage(byte[] data, long deliveredCount) {
        Message msg = mock(Message.class);
        NatsJetStreamMetaData meta = mock(NatsJetStreamMetaData.class);
        when(meta.deliveredCount()).thenReturn(deliveredCount);
        when(msg.metaData()).thenReturn(meta);
        when(msg.getData()).thenReturn(data);
        return msg;
    }

    @Test
    void refreshesSignatureAcrossDelayedNatsRedeliveryAndAcksOn204() throws Exception {
        natsState.outboundPayloads.clear();
        deliveredHeaders.clear();
        deliveredBodies.clear();

        // 1) Caller publishes -> persisted into NATS JetStream immediately
        epochSeconds.set(1_700_000_000L);
        crmWebhooks.contactCreated(new ContactCreated("c-1", "Ada Lovelace", "ada@example.com"));
        assertThat(natsState.outboundPayloads).hasSize(1);
        byte[] storedPayload = natsState.outboundPayloads.get(0);

        // 2) First relay attempt hits 503 with Retry-After: 3 -> nakWithDelay(3s)
        nextStatus.set(503);
        retryAfterSeconds.set(3);
        Message attempt1 = mockJetStreamMessage(storedPayload, 1L);
        relayWorker.deliverOne(attempt1);
        verify(attempt1).nakWithDelay(Duration.ofSeconds(3));

        // 3) 30 minutes pass while receiver recovers (far past 5m replay window!), then second relay attempt succeeds
        epochSeconds.addAndGet(1_800L);
        nextStatus.set(204);
        retryAfterSeconds.set(0);
        Message attempt2 = mockJetStreamMessage(storedPayload, 2L);
        relayWorker.deliverOne(attempt2);
        verify(attempt2).ackSync(Duration.ofSeconds(2));

        // Both attempts carried the SAME webhook-id and body, but a FRESH webhook-timestamp and webhook-signature!
        assertThat(deliveredHeaders).hasSize(2);
        assertThat(deliveredHeaders.get(1).get("Webhook-id"))
                .isEqualTo(deliveredHeaders.get(0).get("Webhook-id"));
        assertThat(deliveredHeaders.get(0).get("Webhook-timestamp")).containsExactly("1700000000");
        assertThat(deliveredHeaders.get(1).get("Webhook-timestamp")).containsExactly("1700001800");
        assertThat(deliveredHeaders.get(1).get("Webhook-signature"))
                .isNotEqualTo(deliveredHeaders.get(0).get("Webhook-signature"));
        assertThat(deliveredBodies.get(1)).isEqualTo(deliveredBodies.get(0));
    }

    @Test
    void routesFatal4xxToDlqStreamAndDeadLetterListenerAndTermsMessage() {
        natsState.outboundPayloads.clear();
        natsState.dlqPayloads.clear();
        deadLetterListener.clear();

        crmWebhooks.contactCreated(new ContactCreated("c-fatal", "Grace Hopper", "grace@example.com"));
        byte[] storedPayload = natsState.outboundPayloads.get(0);

        nextStatus.set(404);
        retryAfterSeconds.set(0);
        Message msg = mockJetStreamMessage(storedPayload, 1L);
        relayWorker.deliverOne(msg);

        verify(msg).term();
        assertThat(natsState.dlqPayloads).hasSize(1);
        assertThat(deadLetterListener.failures()).hasSize(1);
        assertThat(deadLetterListener.failures().get(0).reason()).isEqualTo(DeliveryFailure.Reason.FATAL);
    }
}
