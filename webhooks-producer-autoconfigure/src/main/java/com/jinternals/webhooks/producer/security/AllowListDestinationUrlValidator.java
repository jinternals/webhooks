package com.jinternals.webhooks.producer.security;

import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in {@link DestinationUrlValidator} that permits delivery only to an explicit set of hostnames
 * (SSRF protection; see SPEC.md §19d). Wired when {@code webhooks.producer.security.allowed-hosts} is
 * non-empty. A URL whose host is not on the list — or that is syntactically invalid or has no host — is
 * rejected with a {@link WebhookDeliveryException} before any request is sent.
 *
 * <p>Matching is exact and case-insensitive on the host component (e.g. {@code crm.example.com}).
 * Subdomains are not implied: list each host you intend to allow.
 */
public class AllowListDestinationUrlValidator implements DestinationUrlValidator {

    private final Set<String> allowedHosts;

    public AllowListDestinationUrlValidator(List<String> allowedHosts) {
        this.allowedHosts = new HashSet<>();
        for (String host : allowedHosts) {
            this.allowedHosts.add(host.toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public void validate(String url) {
        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new WebhookDeliveryException("Invalid destination URL: " + SafeUrl.sanitize(url), e);
        }
        if (host == null) {
            throw new WebhookDeliveryException("Destination URL has no host: " + SafeUrl.sanitize(url));
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new WebhookDeliveryException(
                    "Destination host not allowed: " + host + " (not in webhooks.producer.security.allowed-hosts)");
        }
    }
}
