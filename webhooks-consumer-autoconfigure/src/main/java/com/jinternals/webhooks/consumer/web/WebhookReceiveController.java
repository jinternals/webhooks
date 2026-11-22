package com.jinternals.webhooks.consumer.web;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.web.error.EndpointNotConfiguredException;
import com.jinternals.webhooks.consumer.web.receive.WebhookRequestHeaders;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Single generic controller behind every configured listen path (see SPEC.md §3, §10). The matched
 * {@link Endpoint} is set on the request by {@link WebhookEndpointRegistry} so this stays path-agnostic.
 *
 * <p>This is a thin HTTP adapter (Single Responsibility): it extracts the matched endpoint and request
 * headers and delegates to the receive pipeline — verify → dedupe → route → deserialize + validate →
 * metadata + observability → dispatch — which lives in {@link WebhookReceivePipeline}.
 *
 * <p>The toolkit does <strong>not</strong> touch the SLF4J {@code MDC}: correlation / trace ids and log
 * context are the application's concern (see SPEC.md §9b). Add a servlet filter or Micrometer Tracing /
 * OpenTelemetry to populate the {@code MDC}; the stable {@code webhook-id} is available to handlers via
 * {@code WebhookMetadata.webhookId()}.
 *
 * <p>A single endpoint accepts one <em>or</em> many event {@code type}s: the envelope's {@code type}
 * selects the handler, and the endpoint's optional type allow-list ({@link Endpoint#getTypes()})
 * constrains which types it will accept.
 */
public class WebhookReceiveController {

    static final String ENDPOINT_ATTRIBUTE = "webhook.endpoint";

    private final WebhookReceivePipeline pipeline;

    public WebhookReceiveController(WebhookReceivePipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Generic receive handler bound to every configured path. Extracts the matched endpoint and headers,
     * then delegates the processing to {@link WebhookReceivePipeline}. The business-level timing + tracing
     * span lives on the dispatcher ({@code @Observed}, see SPEC.md §13), and actuator's
     * {@code http.server.requests} already covers the HTTP layer. Throws on verification / unknown-type /
     * payload-validation failures; see
     * {@link com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler}.
     */
    public ResponseEntity<Void> handle(HttpServletRequest request, String rawBody) throws WebhookVerificationException {
        Endpoint endpoint = (Endpoint) request.getAttribute(ENDPOINT_ATTRIBUTE);
        if (endpoint == null) {
            // No endpoint matched this path: a routing/config problem, not a sender error. Guard
            // explicitly so endpoint.getSecret() below can never NPE into an opaque 500 (SPEC §14a).
            throw new EndpointNotConfiguredException(request.getRequestURI());
        }
        Map<String, List<String>> headers = WebhookRequestHeaders.collect(request);
        return pipeline.process(request, endpoint, rawBody, headers);
    }
}
