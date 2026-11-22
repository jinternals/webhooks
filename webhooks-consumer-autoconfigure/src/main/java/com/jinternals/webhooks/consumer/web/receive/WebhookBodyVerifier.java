package com.jinternals.webhooks.consumer.web.receive;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.verify.VerificationFailureReason;
import com.jinternals.webhooks.consumer.verify.WebhookVerifier;
import com.jinternals.webhooks.core.secret.SecretProvider;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.util.List;
import java.util.Map;

/**
 * Receive-pipeline stage 1 (see SPEC.md §8, §10): verify the <strong>raw</strong> request body against
 * the matched endpoint's secret(s), never re-serializing before verification. A failure is counted by
 * its low-cardinality reason tag (SPEC §20.6/A4) and rethrown unchanged so the
 * {@link com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler} maps it to a {@code 400}.
 *
 * <p>Single responsibility: HMAC verification + failure metering. It does not parse, route, or dispatch.
 */
public class WebhookBodyVerifier {

    private final WebhookVerifier verifier;
    private final SecretProvider<Endpoint> secretProvider;
    private final WebhookMetrics metrics;

    public WebhookBodyVerifier(
            WebhookVerifier verifier, SecretProvider<Endpoint> secretProvider, WebhookMetrics metrics) {
        this.verifier = verifier;
        this.secretProvider = secretProvider;
        this.metrics = metrics;
    }

    public void verify(Endpoint endpoint, String rawBody, Map<String, List<String>> headers)
            throws WebhookVerificationException {
        try {
            verifier.verify(secretProvider.secretsFor(endpoint), endpoint.getEndpointId(), rawBody, headers);
        } catch (WebhookVerificationException e) {
            metrics.verificationFailed(VerificationFailureReason.from(e).tag());
            throw e;
        }
    }
}
