package com.jinternals.webhooks.producer.transport;

import java.util.List;
import java.util.Map;

/**
 * Pluggable <strong>outbound-request interceptor</strong> for the producer (see SPEC.md §19,
 * <a href="../../../../../../../../docs/guides/transport-authentication.md">transport authentication</a>).
 * An interceptor observes a fully-signed webhook request and returns the headers to actually send — the
 * natural seam for adding <strong>transport authentication</strong> such as an OAuth2
 * {@code Authorization: Bearer} token, <em>without</em> replacing the delivery transport.
 *
 * <p>Interceptors run <strong>inside</strong> the default transport chain, between the retry layer and the
 * HTTP send: {@code RetryingWebhookTransport → InterceptingWebhookTransport → RestClientWebhookTransport}.
 * This is deliberate and is the whole point of the seam:
 *
 * <ul>
 *   <li>the built-in retry/backoff, {@code Retry-After} handling, and dead-letter hook still apply
 *       ({@link RetryingWebhookTransport});</li>
 *   <li>the SSRF/destination guard ({@code DestinationUrlValidator}) and URL redaction still apply
 *       ({@link RestClientWebhookTransport});</li>
 *   <li>because interception happens per attempt, a header derived from a short-lived credential (e.g. a
 *       bearer token) is <strong>re-derived on every retry</strong> — if a token expires during backoff on a
 *       retryable failure ({@code 5xx}/{@code 429}/timeout), the next attempt picks up a freshly minted one.</li>
 * </ul>
 *
 * <p>Contrast with publishing your own {@code @Bean WebhookTransport}: that <em>replaces</em> the entire
 * chain and silently drops retry <em>and</em> the SSRF guard unless you re-assemble them by hand. Reach for
 * a replacement transport only when you are genuinely changing the <em>delivery mechanism</em> (e.g.
 * enqueue to SQS/Kafka); reach for an interceptor when you are only shaping the outbound <em>request</em>.
 *
 * <p>Register one or more as ordinary beans; all present {@code WebhookRequestInterceptor} beans are applied
 * in Spring {@link org.springframework.core.annotation.Order @Order} order, each receiving the previous
 * interceptor's output, so independent concerns (auth, tenancy, tracing headers) compose.
 *
 * <p><strong>The returned map must be a new map; do not mutate the input.</strong> The signed
 * {@code webhook-*} headers are present in the input and must be preserved (return them unchanged alongside
 * your additions) — dropping them would make the delivery fail verification at the receiver.
 *
 * <p><strong>Never log credential headers.</strong> The toolkit does not log request headers; an
 * {@code Authorization} value you add is a secret and must stay out of logs.
 */
@FunctionalInterface
public interface WebhookRequestInterceptor {

    /**
     * Return the headers to send for this request. Typically the input headers plus one or two additions
     * (e.g. an {@code Authorization} bearer token).
     *
     * @param url     the absolute destination URL (observe only; delivery always uses the real URL)
     * @param headers the current headers as name -&gt; list of values, including the signed
     *                {@code webhook-*} headers; treat as effectively immutable
     * @param body    the raw JSON body exactly as signed (observe only; changing it would break the
     *                signature)
     * @return the headers to send — a <strong>new</strong> map; must still contain the signed
     *         {@code webhook-*} headers
     */
    Map<String, List<String>> intercept(String url, Map<String, List<String>> headers, String body);
}
