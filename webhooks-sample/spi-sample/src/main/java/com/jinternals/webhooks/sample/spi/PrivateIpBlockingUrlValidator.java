package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Sample custom {@link DestinationUrlValidator} demonstrating a CIDR / link-local / cloud-metadata SSRF
 * block policy (rejects {@code 169.254.0.0/16} link-local, RFC-1918 site-local, IPv6 unique-local
 * {@code fc00::/7}, multicast, and {@code 0.0.0.0}/{@code ::} wildcard addresses while allowing loopback for
 * local integration tests when configured).
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
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || host == null
                    || host.isBlank()) {
                throw new WebhookDeliveryException(
                        "Invalid or unresolvable destination URL (http/https host required): " + SafeUrl.sanitize(url));
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new WebhookDeliveryException("Invalid or unresolvable destination URL: " + SafeUrl.sanitize(url));
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isIpv6UniqueLocal(address)) {
                    throw new WebhookDeliveryException(
                            "Blocked private/link-local destination IP for URL: " + SafeUrl.sanitize(url));
                }
                if (!allowLoopback && address.isLoopbackAddress()) {
                    throw new WebhookDeliveryException(
                            "Blocked loopback destination IP for URL: " + SafeUrl.sanitize(url));
                }
            }
        } catch (WebhookDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new WebhookDeliveryException("Invalid or unresolvable destination URL: " + SafeUrl.sanitize(url), e);
        }
    }

    /** RFC 4193 IPv6 Unique Local Addresses ({@code fc00::/7} — i.e., {@code fc00::/8} and {@code fd00::/8}). */
    private static boolean isIpv6UniqueLocal(InetAddress address) {
        if (address instanceof Inet6Address inet6) {
            byte[] octets = inet6.getAddress();
            return (octets[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
