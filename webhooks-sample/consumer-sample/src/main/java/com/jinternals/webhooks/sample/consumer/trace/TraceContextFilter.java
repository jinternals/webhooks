package com.jinternals.webhooks.sample.consumer.trace;

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

/**
 * Consumer-side half of the <strong>end-to-end trace/correlation example</strong> (SPEC.md §9b).
 *
 * <p>The webhooks toolkit owns the <em>protocol</em> (signature verification, replay, idempotency) and
 * writes nothing to the SLF4J {@code MDC}; correlation is the <strong>application's</strong> concern. This
 * filter is the app code that binds the correlation context for every inbound delivery, running
 * <em>before</em> the webhook controller so the very first log line — including a verification-failure line
 * — is already correlated:
 *
 * <ul>
 *   <li><strong>trace id</strong> — read from the agreed {@link #TRACE_HEADER} the producer sent (see the
 *       producer sample's {@code TraceHeaderWebhookInterceptor}); a fresh UUID is minted when absent so the
 *       delivery is always correlatable. Bound under {@link #TRACE_MDC_KEY}.</li>
 *   <li><strong>webhook id</strong> — the stable {@code webhook-id} from the Standard Webhooks envelope,
 *       constant across the sender's retries. Bound under {@link #WEBHOOK_ID_MDC_KEY}.</li>
 * </ul>
 *
 * <p>Both keys are cleared in a {@code finally} so they never leak onto an unrelated request on a pooled
 * thread. The toolkit then propagates this {@code MDC} across the async handler hop, so the handler's own log
 * lines stay correlated too. The {@code webhook-id} is also always available to handlers via
 * {@code WebhookMetadata.webhookId()}; this filter additionally surfaces it on the log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    /** The header both sides agreed on out of band; matches the producer sample's interceptor. */
    public static final String TRACE_HEADER = "x-trace-id";

    /** The Standard Webhooks message-id header; the stable per-event correlation key. */
    public static final String WEBHOOK_ID_HEADER = "webhook-id";

    /** MDC key for the trace id; referenced by the log pattern ({@code %X{traceId}}). */
    public static final String TRACE_MDC_KEY = "traceId";

    /** MDC key for the webhook id; referenced by the log pattern ({@code %X{webhook.id}}). */
    public static final String WEBHOOK_ID_MDC_KEY = "webhook.id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TRACE_MDC_KEY, traceId);

        String webhookId = request.getHeader(WEBHOOK_ID_HEADER);
        if (webhookId != null && !webhookId.isBlank()) {
            MDC.put(WEBHOOK_ID_MDC_KEY, webhookId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_MDC_KEY);
            MDC.remove(WEBHOOK_ID_MDC_KEY);
        }
    }
}
