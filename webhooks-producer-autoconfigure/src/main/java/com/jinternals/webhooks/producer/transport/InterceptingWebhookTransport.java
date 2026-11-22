package com.jinternals.webhooks.producer.transport;

import java.util.List;
import java.util.Map;

/**
 * {@link WebhookTransport} decorator that applies an ordered chain of {@link WebhookRequestInterceptor}s
 * to the outbound headers before delegating the actual send (see SPEC.md §19). It sits between the retry
 * layer and the HTTP send in the default chain
 * ({@code RetryingWebhookTransport → InterceptingWebhookTransport → RestClientWebhookTransport}), so
 * interceptor-added headers (e.g. an OAuth2 bearer token) are present on every attempt while the retry,
 * {@code Retry-After}, dead-letter, and SSRF-guard behaviours of the surrounding transports are preserved.
 *
 * <p>With no interceptors configured this is a transparent pass-through: it delegates unchanged, so the
 * producer stays drop-in and the default behaviour is byte-for-byte identical to sending without it.
 *
 * <p>Each interceptor receives the <em>previous</em> interceptor's output, so independent header concerns
 * compose in order. The decorator never mutates the headers map itself; it only forwards whatever the
 * chain returns. A misbehaving interceptor that returns {@code null} is rejected with a clear error rather
 * than delivering with no headers (which would strip the signed {@code webhook-*} headers and fail
 * verification at the receiver).
 */
public class InterceptingWebhookTransport implements WebhookTransport {

    private final WebhookTransport delegate;
    private final List<WebhookRequestInterceptor> interceptors;

    public InterceptingWebhookTransport(WebhookTransport delegate, List<WebhookRequestInterceptor> interceptors) {
        this.delegate = delegate;
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        Map<String, List<String>> current = headers;
        for (WebhookRequestInterceptor interceptor : interceptors) {
            Map<String, List<String>> next = interceptor.intercept(url, current, body);
            if (next == null) {
                throw new WebhookDeliveryException(
                        "Webhook request interceptor " + interceptor.getClass().getName() + " returned null headers");
            }
            current = next;
        }
        delegate.send(url, current, body);
    }
}
