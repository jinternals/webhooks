package com.jinternals.webhooks.core.config;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Thrown at <strong>startup</strong> when webhook configuration is invalid in a way the context cannot
 * recover from — a missing or malformed signing key, a missing endpoint id for the asymmetric {@code v2a}
 * scheme, an unreachable destination URL, and the like (see SPEC.md §14a, §19d, §8e, §19i). It carries both
 * a human-readable {@link #getMessage() description} of what is wrong and a concrete {@link #getAction()
 * remediation action}, so each side's Spring Boot {@code FailureAnalyzer} can render a clean
 * {@code APPLICATION FAILED TO START} report (description + action) instead of a raw stack trace.
 *
 * <p>This lives in {@code webhooks-core} because <em>both</em> the producer's {@code DestinationValidator}
 * and the consumer's {@code SecretValidator} raise it, and the shared {@code SigningSchemeValidator} raises
 * it for the {@code v2a} invariants common to both sides. It is a {@link RuntimeException} so it aborts
 * context refresh; the module-local {@code FailureAnalyzer} is what turns it into the friendly report.
 *
 * <p>The real secret value is <strong>never</strong> placed in the description or the action — a
 * misconfiguration report is safe to log.
 */
public class WebhookConfigurationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String action;

    /**
     * @param description what is wrong (becomes the failure report's <em>Description</em> and the exception
     *                    message); must never contain a secret value
     * @param action      how to fix it (becomes the failure report's <em>Action</em>)
     */
    public WebhookConfigurationException(String description, String action) {
        super(description);
        this.action = action;
    }

    /**
     * @param description what is wrong (becomes the failure report's <em>Description</em> and the exception
     *                    message); must never contain a secret value
     * @param action      how to fix it (becomes the failure report's <em>Action</em>)
     * @param cause       the underlying failure (e.g. a key-decode or URL-parse error), kept for the stack
     */
    public WebhookConfigurationException(String description, String action, @Nullable Throwable cause) {
        super(description, cause);
        this.action = action;
    }

    /** The concrete remediation step, rendered as the failure report's <em>Action</em>. */
    public String getAction() {
        return action;
    }
}
