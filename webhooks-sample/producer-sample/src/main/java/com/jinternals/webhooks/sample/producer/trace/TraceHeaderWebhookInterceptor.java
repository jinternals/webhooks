package com.jinternals.webhooks.sample.producer.trace;

import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Producer-side trace propagation for the <strong>end-to-end trace/correlation example</strong> (SPEC.md
 * §9b). This is a plain {@link WebhookRequestInterceptor} bean — the <em>single</em> outbound-request-shaping
 * strategy the toolkit offers, the same seam used for OAuth2 transport auth and multi-tenancy routing.
 *
 * <p>It copies the current unit of work's trace id (bound to the {@code MDC} by the application — here
 * {@code PublishController} seeds it; in a real app a Micrometer Tracing / OpenTelemetry bridge or an inbound
 * servlet filter would) onto the outbound webhook under the agreed header {@link #TRACE_HEADER}. The consumer
 * sample reads that header back into its own {@code MDC}, so one trace id spans both services and every log
 * line on both sides carries it.
 *
 * <p>Interceptors run <em>inside</em> the default transport chain, so retry, {@code Retry-After} handling,
 * and the SSRF guard are all preserved. The returned map is a <strong>new</strong> map that keeps the signed
 * {@code webhook-*} headers untouched — the trace header is additive and is <em>not</em> covered by the
 * signature (it is a correlation hint, never a security control).
 */
@Component
public class TraceHeaderWebhookInterceptor implements WebhookRequestInterceptor {

    /** The header both sides agreed on out of band; matches the consumer sample's filter. */
    public static final String TRACE_HEADER = "x-trace-id";

    /** MDC key the trace id is read from; the application binds it (see {@code PublishController}). */
    public static final String TRACE_MDC_KEY = "traceId";

    @Override
    public Map<String, List<String>> intercept(String url, Map<String, List<String>> headers, String body) {
        String traceId = MDC.get(TRACE_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return headers; // nothing to propagate
        }
        // New map; preserve every signed webhook-* header and add the agreed trace header alongside them.
        Map<String, List<String>> out = new LinkedHashMap<>(headers);
        out.put(TRACE_HEADER, List.of(traceId));
        return out;
    }
}
