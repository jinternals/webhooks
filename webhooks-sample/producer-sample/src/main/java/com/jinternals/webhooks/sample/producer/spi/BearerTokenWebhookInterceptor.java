package com.jinternals.webhooks.sample.producer.spi;

import com.jinternals.webhooks.producer.transport.WebhookRequestInterceptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sample {@link WebhookRequestInterceptor} SPI implementation for OAuth2 / bearer-token transport
 * authentication (see {@code docs/reference/beans-and-spis.md} and
 * {@code docs/guides/transport-authentication.md}).
 *
 * <p>Runs inside {@code RetryingWebhookTransport -> InterceptingWebhookTransport -> RestClientWebhookTransport},
 * so each retry re-evaluates {@code tokenSupplier} while keeping retry, {@code Retry-After}, and the SSRF
 * guard intact. Always returns a <strong>new</strong> map preserving the signed {@code webhook-*} headers.
 */
public class BearerTokenWebhookInterceptor implements WebhookRequestInterceptor {

    private final Supplier<String> tokenSupplier;

    public BearerTokenWebhookInterceptor(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public Map<String, List<String>> intercept(String url, Map<String, List<String>> headers, String body) {
        String token = tokenSupplier.get();
        Map<String, List<String>> out = new LinkedHashMap<>(headers);
        out.put("Authorization", List.of("Bearer " + token));
        return out;
    }
}
