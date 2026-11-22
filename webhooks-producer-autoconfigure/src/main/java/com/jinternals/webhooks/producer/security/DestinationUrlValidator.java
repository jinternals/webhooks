package com.jinternals.webhooks.producer.security;

import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;

/**
 * Outbound destination-URL guard (SSRF protection; see SPEC.md §19d). This is the <strong>extension
 * point (SPI)</strong> for validating where the producer is allowed to deliver, checked immediately
 * before a request is sent.
 *
 * <p>The shipped implementations are {@link AllowAllDestinationUrlValidator} (default — no restriction,
 * because destinations come from trusted host configuration) and {@link AllowListDestinationUrlValidator}
 * (opt-in — only an explicit set of hostnames may be delivered to). Provide your own
 * {@code @Bean DestinationUrlValidator} for a different policy (CIDR ranges, private-IP blocking, DNS
 * rebinding checks); the auto-configuration backs off when a bean is already present
 * ({@code @ConditionalOnMissingBean}).
 *
 * <p><strong>Why it matters.</strong> If a destination URL can ever be influenced by untrusted input, an
 * unrestricted producer can be coerced into requesting internal addresses (cloud metadata endpoints,
 * private services) — a Server-Side Request Forgery. An allow-list closes that off.
 */
public interface DestinationUrlValidator {

    /**
     * Validate that delivery to {@code url} is permitted.
     *
     * @param url the absolute destination URL about to be requested
     * @throws WebhookDeliveryException if delivery to this URL is not allowed
     */
    void validate(String url);
}
