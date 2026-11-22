package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.net.InetAddress;
import java.net.URI;

/**
 * Sample custom {@link DestinationUrlValidator} demonstrating a CIDR / link-local / cloud-metadata SSRF
 * block policy (rejects {@code 169.254.0.0/16} link-local and site-local RFC-1918 addresses while allowing
 * loopback for local integration tests when configured).
 */
public class PrivateIpBlockingUrlValidator implements DestinationUrlValidator {

    private final boolean allowLoopback;

    public PrivateIpBlockingUrlValidator(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    @Override
    public void validate(String url) {
        try {
            URI uri = URI.create(url);
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                throw new WebhookDeliveryException(
                        "Blocked private/link-local destination IP for URL: " + SafeUrl.sanitize(url));
            }
            if (!allowLoopback && address.isLoopbackAddress()) {
                throw new WebhookDeliveryException("Blocked loopback destination IP for URL: " + SafeUrl.sanitize(url));
            }
        } catch (WebhookDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new WebhookDeliveryException("Invalid or unresolvable destination URL: " + SafeUrl.sanitize(url), e);
        }
    }
}
