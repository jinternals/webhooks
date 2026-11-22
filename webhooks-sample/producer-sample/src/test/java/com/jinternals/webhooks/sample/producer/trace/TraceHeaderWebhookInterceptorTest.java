package com.jinternals.webhooks.sample.producer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceHeaderWebhookInterceptorTest {

    private final TraceHeaderWebhookInterceptor interceptor = new TraceHeaderWebhookInterceptor();

    private static final Map<String, List<String>> SIGNED_HEADERS = Map.of(
            "webhook-id", List.of("msg_1"),
            "webhook-timestamp", List.of("1700000000"),
            "webhook-signature", List.of("v1,sig"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void addsTheTraceHeaderFromMdcWhilePreservingTheSignedHeaders() {
        MDC.put(TraceHeaderWebhookInterceptor.TRACE_MDC_KEY, "trace-abc");

        Map<String, List<String>> out = interceptor.intercept("https://dest/webhooks", SIGNED_HEADERS, "{}");

        // The agreed trace header is added…
        assertThat(out).containsEntry(TraceHeaderWebhookInterceptor.TRACE_HEADER, List.of("trace-abc"));
        // …and every signed webhook-* header is preserved untouched.
        assertThat(out)
                .containsEntry("webhook-id", List.of("msg_1"))
                .containsEntry("webhook-timestamp", List.of("1700000000"))
                .containsEntry("webhook-signature", List.of("v1,sig"));
    }

    @Test
    void returnsTheHeadersUnchangedWhenNoTraceIdIsBound() {
        Map<String, List<String>> out = interceptor.intercept("https://dest/webhooks", SIGNED_HEADERS, "{}");

        assertThat(out).isEqualTo(SIGNED_HEADERS);
        assertThat(out).doesNotContainKey(TraceHeaderWebhookInterceptor.TRACE_HEADER);
    }
}
