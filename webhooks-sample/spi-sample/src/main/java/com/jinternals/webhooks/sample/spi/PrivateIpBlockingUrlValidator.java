package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Sample custom {@link DestinationUrlValidator} demonstrating a CIDR / link-local / cloud-metadata SSRF
 * block policy (rejects {@code 169.254.0.0/16} link-local, RFC-1918 site-local, RFC-6598 CGNAT
 * {@code 100.64.0.0/10} including cloud metadata {@code 100.100.100.200}, {@code 0.0.0.0/8}, benchmark
 * {@code 198.18.0.0/15}, reserved {@code 240.0.0.0/4}, IPv6 unique-local {@code fc00::/7}, NAT64
 * {@code 64:ff9b::/96}, and multicast addresses while allowing loopback for local integration tests when
 * configured).
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
                        || isBlockedSpecialRange(address)) {
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

    private static boolean isBlockedSpecialRange(InetAddress address) {
        if (address instanceof Inet4Address inet4) {
            byte[] octets = inet4.getAddress();
            int b0 = octets[0] & 0xff;
            int b1 = octets[1] & 0xff;
            // 0.0.0.0/8 ("this network"), 100.64.0.0/10 (RFC 6598 CGNAT / Alibaba metadata 100.100.100.200),
            // 198.18.0.0/15 (RFC 2544 benchmark), and 240.0.0.0/4 (reserved / broadcast).
            return b0 == 0
                    || (b0 == 100 && (b1 & 0xc0) == 64)
                    || (b0 == 198 && (b1 & 0xfe) == 18)
                    || (b0 & 0xf0) == 240;
        }
        if (address instanceof Inet6Address inet6) {
            byte[] octets = inet6.getAddress();
            // RFC 4193 Unique Local (fc00::/7) or RFC 6052 NAT64 well-known prefix (64:ff9b::/96).
            if ((octets[0] & 0xfe) == 0xfc) {
                return true;
            }
            return octets[0] == 0x00
                    && octets[1] == 0x64
                    && (octets[2] & 0xff) == 0xff
                    && (octets[3] & 0xff) == 0x9b
                    && octets[4] == 0
                    && octets[5] == 0
                    && octets[6] == 0
                    && octets[7] == 0
                    && octets[8] == 0
                    && octets[9] == 0
                    && octets[10] == 0
                    && octets[11] == 0;
        }
        return false;
    }
}
