package com.jinternals.webhooks.producer.security;

import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Opt-in {@link DestinationUrlValidator} that permits delivery only to {@code http}/{@code https} URLs on an
 * explicit set of hostnames (SSRF protection; see SPEC.md §19d). Wired when
 * {@code webhooks.producer.security.allowed-hosts} is non-empty. A URL whose scheme is not {@code http} or
 * {@code https}, whose host is not on the list, or that is syntactically invalid or has no host is rejected
 * with a {@link WebhookDeliveryException} before any request is sent.
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
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new WebhookDeliveryException("Invalid destination URL: " + SafeUrl.sanitize(url), e);
        }
        if (!isHttpScheme(uri.getScheme())) {
            throw new WebhookDeliveryException(
                    "Unsupported destination URL scheme (expected http or https): " + SafeUrl.sanitize(url));
        }
        String host = uri.getHost();
        if (host == null) {
            throw new WebhookDeliveryException("Destination URL has no host: " + SafeUrl.sanitize(url));
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new WebhookDeliveryException(
                    "Destination host not allowed: " + host + " (not in webhooks.producer.security.allowed-hosts)");
        }
    }

    private static boolean isHttpScheme(@Nullable String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
