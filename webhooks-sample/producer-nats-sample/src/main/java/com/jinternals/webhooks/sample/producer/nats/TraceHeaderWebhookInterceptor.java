package com.jinternals.webhooks.sample.producer.nats;

import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TraceHeaderWebhookInterceptor implements WebhookRequestInterceptor {

    public static final String TRACE_HEADER = "x-trace-id";

    @Override
    public Map<String, List<String>> intercept(String url, Map<String, List<String>> headers, String body) {
        String traceId = MDC.get(NatsJetStreamWebhookTransport.TRACE_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return headers;
        }
        Map<String, List<String>> out = new LinkedHashMap<>(headers);
        out.put(TRACE_HEADER, List.of(traceId));
        return out;
    }
}
