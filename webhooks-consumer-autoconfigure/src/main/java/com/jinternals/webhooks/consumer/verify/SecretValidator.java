package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.core.config.WebhookConfigurationException;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.core.sign.Ed25519Keys;
import com.jinternals.webhooks.core.sign.SigningSchemeValidator;
import com.standardwebhooks.Webhook;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Validates every configured endpoint secret at <strong>startup</strong> (see SPEC.md §14a). Each
 * endpoint must resolve to at least one secret, each symmetric ({@code whsec_}) secret must be a well-formed
 * Standard Webhooks secret (constructing a {@link Webhook} would otherwise throw), and each asymmetric
 * ({@code whpk_}) endpoint must satisfy the {@code v2a} rules — a well-formed Ed25519 public key and a valid,
 * dot-free {@code endpoint-id} (see SPEC.md §8e). A problem fails the context at boot — fast and loud —
 * instead of surfacing later as a per-request {@code 500} that a sender would retry forever.
 *
 * <p>Each failure is a {@link WebhookConfigurationException} (description + action), which
 * {@code WebhookConfigurationFailureAnalyzer} renders as a clean {@code APPLICATION FAILED TO START} report.
 *
 * <p>Enabled by default; disable with {@code webhooks.consumer.validate-secrets-on-startup: false}.
 * Also skipped when signature verification is turned off
 * ({@code webhooks.consumer.signature-verification-enabled: false}), since endpoints then need no secret.
 * The real secret value is never logged.
 */
public class SecretValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecretValidator.class);

    private final WebhookProperties properties;
    private final SecretProvider<Endpoint> secretProvider;

    public SecretValidator(WebhookProperties properties, SecretProvider<Endpoint> secretProvider) {
        this.properties = properties;
        this.secretProvider = secretProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getConsumer().isValidateSecretsOnStartup()) {
            return;
        }
        if (!properties.getConsumer().isSignatureVerificationEnabled()) {
            // Signature verification is disabled, so endpoints need no secret — nothing to validate.
            return;
        }
        for (Endpoint endpoint : properties.getConsumer().getEndpoints()) {
            validateEndpoint(endpoint);
        }
    }

    private void validateEndpoint(Endpoint endpoint) {
        List<String> secrets = secretProvider.secretsFor(endpoint);
        String path = endpoint.getPath();
        if (secrets.isEmpty()) {
            throw new WebhookConfigurationException(
                    "No webhook secret configured for endpoint " + path,
                    "Set the secret for this endpoint to a whsec_ HMAC secret (v1) or a whpk_ Ed25519 public "
                            + "key (v2a), or disable verification with "
                            + "webhooks.consumer.signature-verification-enabled=false.");
        }
        String target = "endpoint " + path;
        // v2a (whpk_) rules: endpoint-id required + dot-free, each Ed25519 public key well-formed, and no
        // producer-side private key (whsk_) used where a public key belongs.
        SigningSchemeValidator.validateAsymmetric(
                target, "the endpoint-id for " + path, endpoint.getEndpointId(), secrets, false);
        if (SigningSchemeValidator.isEndpointIdIgnored(endpoint.getEndpointId(), secrets)) {
            log.warn(
                    "endpoint-id is set on {} but all its secrets are symmetric (whsec_, v1); endpoint-id is "
                            + "only used for the asymmetric v2a scheme and will be ignored",
                    target);
        }
        for (String secret : secrets) {
            validateSymmetricSecret(path, secret);
        }
        log.info("Validated {} secret(s) for webhook endpoint {}", secrets.size(), path);
    }

    /** Validate a {@code whsec_} HMAC secret via the upstream library; Ed25519 keys are handled above. */
    private void validateSymmetricSecret(String path, String secret) {
        // Only whpk_ (public) or whsec_ secrets can reach here: any whsk_ (private) key was already rejected
        // by validateAsymmetric (a consumer verifies with the public key), so there is no whsk_ branch to test.
        if (Ed25519Keys.isPublicKey(secret)) {
            return; // asymmetric material already validated by SigningSchemeValidator
        }
        try {
            new Webhook(secret);
        } catch (Exception e) {
            throw new WebhookConfigurationException(
                    "Invalid webhook secret for endpoint " + path + ": " + e.getMessage(),
                    "Provide a valid Standard Webhooks symmetric secret (whsec_ followed by Base64 key "
                            + "material) for this endpoint, or use a whpk_ Ed25519 public key for v2a.",
                    e);
        }
    }
}
