package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.core.model.WebhookHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "x-trace-id";
    public static final String TRACE_MDC_KEY = "traceId";
    public static final String WEBHOOK_ID_MDC_KEY = "webhook.id";

    private static final int MAX_MDC_VALUE_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (!isSafeMdcValue(traceId)) {
            traceId = UUID.randomUUID().toString();
        }
        String webhookId = request.getHeader(WebhookHeaders.ID);
        MDC.put(TRACE_MDC_KEY, traceId);
        if (isSafeMdcValue(webhookId)) {
            MDC.put(WEBHOOK_ID_MDC_KEY, webhookId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_MDC_KEY);
            MDC.remove(WEBHOOK_ID_MDC_KEY);
        }
    }

    private static boolean isSafeMdcValue(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_MDC_VALUE_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }
}
