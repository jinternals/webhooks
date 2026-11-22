package com.jinternals.webhooks.producer.config;

import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.SigningSchemeValidator;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import com.jinternals.webhooks.producer.transport.WebhookDeliveryException;
import com.standardwebhooks.Webhook;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Validates every configured producer destination at <strong>startup</strong> (see SPEC.md §19d), mirroring
 * the consumer's {@code com.jinternals.webhooks.consumer.verify.SecretValidator}. Each destination must have
 * a non-blank, parseable URL with a host that passes the active {@link DestinationUrlValidator} guard
 * (so an allow-list miss is caught at boot, not on the first delivery), and must resolve to at least one
 * signing secret. A problem fails the context at boot — fast and loud — instead of surfacing later as the
 * first business event's {@code WebhookDeliveryException}, by which point the event has already been
 * produced and may be dropped unless the host has its own durable outbox.
 *
 * <p>Each failure is a {@link WebhookConfigurationException} (description + action), which
 * {@code WebhookConfigurationFailureAnalyzer} renders as a clean {@code APPLICATION FAILED TO START} report
 * instead of a raw stack trace.
 *
 * <p>Enabled by default; disable with {@code webhooks.producer.validate-destinations-on-startup: false}.
 * The real secret value is never logged.
 */
public class DestinationValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DestinationValidator.class);

    private final WebhookProducerProperties properties;
    private final DestinationUrlValidator urlValidator;
    private final SecretProvider<Destination> secretProvider;

    public DestinationValidator(
            WebhookProducerProperties properties,
            DestinationUrlValidator urlValidator,
            SecretProvider<Destination> secretProvider) {
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.secretProvider = secretProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isValidateDestinationsOnStartup()) {
            return;
        }
        for (Map.Entry<String, Destination> entry : properties.getDestinations().entrySet()) {
            String name = entry.getKey();
            Destination destination = entry.getValue();
            validateUrl(name, destination.getUrl());
            if (properties.isSigningEnabled()) {
                validateSecrets(name, destination);
            }
            log.info("Validated webhook destination '{}'", name);
        }
    }

    private void validateUrl(String name, String url) {
        String base = "webhooks.producer.destinations." + name;
        if (url == null || url.isBlank()) {
            throw new WebhookConfigurationException(
                    "No URL configured for webhook destination '" + name + "'",
                    "Set " + base + ".url to the absolute HTTPS URL the signed envelope should be POSTed to.");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // URISyntaxException#getMessage() embeds the raw input URL ("reason at index N: <url>"), so redact
            // it before building the report — otherwise userinfo credentials or query tokens leak to startup logs.
            throw new WebhookConfigurationException(
                    "Invalid URL for webhook destination '" + name + "': " + SafeUrl.redactMessage(e.getMessage(), url),
                    "Correct " + base + ".url to a valid absolute URL (scheme + host), e.g. "
                            + "https://crm.example.com/webhooks.",
                    e);
        }
        if (uri.getHost() == null) {
            throw new WebhookConfigurationException(
                    "URL for webhook destination '" + name + "' has no host: " + SafeUrl.sanitize(url),
                    "Set " + base + ".url to an absolute URL that includes a host, e.g. "
                            + "https://crm.example.com/webhooks.");
        }
        // Run the active URL guard now so an allow-list miss (or any custom policy rejection) fails at boot
        // rather than on the first delivery.
        try {
            urlValidator.validate(url);
        } catch (WebhookDeliveryException e) {
            throw new WebhookConfigurationException(
                    "URL for webhook destination '" + name + "' is rejected by the destination URL guard: "
                            + e.getMessage(),
                    "Add the destination host to webhooks.producer.security.allowed-hosts, or correct " + base
                            + ".url to an allowed host.",
                    e);
        }
    }

    private void validateSecrets(String name, Destination destination) {
        String base = "webhooks.producer.destinations." + name;
        List<String> secrets = secretProvider.secretsFor(destination);
        if (secrets.isEmpty()) {
            throw new WebhookConfigurationException(
                    "No signing secret configured for webhook destination '" + name + "'",
                    "Set " + base + ".secret to a whsec_ HMAC secret (v1) or a whsk_ Ed25519 private key "
                            + "(v2a), or disable signing with webhooks.producer.signing-enabled=false.");
        }
        String target = "webhook destination '" + name + "'";
        // v2a (whsk_) rules: endpoint-id required + dot-free, each Ed25519 private key well-formed, and no
        // consumer-side public key (whpk_) used where a private key belongs.
        SigningSchemeValidator.validateAsymmetric(
                target, base + ".endpoint-id", destination.getEndpointId(), secrets, true);
        if (SigningSchemeValidator.isEndpointIdIgnored(destination.getEndpointId(), secrets)) {
            log.warn(
                    "endpoint-id is set on {} but all its secrets are symmetric (whsec_, v1); endpoint-id is "
                            + "only used for the asymmetric v2a scheme and will be ignored",
                    target);
        }
        for (String secret : secrets) {
            validateSymmetricSecret(name, base, secret);
        }
    }

    /** Validate a {@code whsec_} HMAC secret via the upstream library; Ed25519 keys are handled above. */
    private void validateSymmetricSecret(String name, String base, String secret) {
        // Only whsk_ (private) or whsec_ secrets can reach here: any whpk_ (public) key was already rejected
        // by validateAsymmetric (a producer signs with the private key), so there is no whpk_ branch to test.
        if (Ed25519Keys.isPrivateKey(secret)) {
            return; // asymmetric material already validated by SigningSchemeValidator
        }
        try {
            new Webhook(secret);
        } catch (Exception e) {
            throw new WebhookConfigurationException(
                    "Invalid signing secret for webhook destination '" + name + "': " + e.getMessage(),
                    "Provide a valid Standard Webhooks symmetric secret (whsec_ followed by Base64 key "
                            + "material) for " + base + ".secret, or use a whsk_ Ed25519 private key for v2a.",
                    e);
        }
    }
}
