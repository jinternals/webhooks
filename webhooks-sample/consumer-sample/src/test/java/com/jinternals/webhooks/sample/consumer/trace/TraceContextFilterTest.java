package com.jinternals.webhooks.sample.consumer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceContextFilterTest {

    private final TraceContextFilter filter = new TraceContextFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void bindsTheIncomingTraceAndWebhookIdThenClearsThemInFinally() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContextFilter.TRACE_HEADER, "trace-from-producer");
        request.addHeader(TraceContextFilter.WEBHOOK_ID_HEADER, "msg_42");

        AtomicReference<String> traceDuringChain = new AtomicReference<>();
        AtomicReference<String> webhookIdDuringChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                // The MDC is visible to downstream (the webhook controller) while the chain runs.
                traceDuringChain.set(MDC.get(TraceContextFilter.TRACE_MDC_KEY));
                webhookIdDuringChain.set(MDC.get(TraceContextFilter.WEBHOOK_ID_MDC_KEY));
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(traceDuringChain.get()).isEqualTo("trace-from-producer");
        assertThat(webhookIdDuringChain.get()).isEqualTo("msg_42");
        // Cleared afterwards so nothing leaks onto a pooled thread's next request.
        assertThat(MDC.get(TraceContextFilter.TRACE_MDC_KEY)).isNull();
        assertThat(MDC.get(TraceContextFilter.WEBHOOK_ID_MDC_KEY)).isNull();
    }

    @Test
    void mintsAFreshTraceIdWhenTheHeaderIsAbsentOrBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContextFilter.TRACE_HEADER, "   "); // blank -> treated as absent

        AtomicReference<String> traceDuringChain = new AtomicReference<>();
        AtomicReference<String> webhookIdDuringChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                traceDuringChain.set(MDC.get(TraceContextFilter.TRACE_MDC_KEY));
                webhookIdDuringChain.set(MDC.get(TraceContextFilter.WEBHOOK_ID_MDC_KEY));
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // A fresh id was minted so the delivery is still correlatable; no webhook-id header -> none bound.
        assertThat(traceDuringChain.get()).isNotBlank();
        assertThat(webhookIdDuringChain.get()).isNull();
    }

    @Test
    void rejectsControlCharactersAndNewlinesInUntrustedHeadersToPreventLogInjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContextFilter.TRACE_HEADER, "forged\nERROR injected");
        request.addHeader(TraceContextFilter.WEBHOOK_ID_HEADER, "msg_1\r\nforged");

        AtomicReference<String> traceDuringChain = new AtomicReference<>();
        AtomicReference<String> webhookIdDuringChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                traceDuringChain.set(MDC.get(TraceContextFilter.TRACE_MDC_KEY));
                webhookIdDuringChain.set(MDC.get(TraceContextFilter.WEBHOOK_ID_MDC_KEY));
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(traceDuringChain.get()).doesNotContain("\n").doesNotContain("forged");
        assertThat(webhookIdDuringChain.get()).isNull();
    }
}
