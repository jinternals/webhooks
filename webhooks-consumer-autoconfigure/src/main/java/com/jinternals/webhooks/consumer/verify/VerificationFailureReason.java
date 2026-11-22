package com.jinternals.webhooks.consumer.verify;

import com.standardwebhooks.exceptions.WebhookVerificationException;

/**
 * Low-cardinality {@code reason} vocabulary for the {@code webhook.verification.failed} counter
 * (SPEC §20.6/A4). Each constant owns the metric {@link #tag()} text, so the vocabulary lives in one
 * typed place instead of scattered string literals.
 *
 * <p>The reason is <strong>set at the throw site</strong>: this toolkit's verification code throws a
 * {@link WebhookVerificationFailure} carrying the constant, so {@link #from(WebhookVerificationException)}
 * reads it back directly rather than matching on message text. A plain {@link WebhookVerificationException}
 * — e.g. one a host-supplied {@code TimestampValidator} threw, whose message we do not control — carries
 * no typed reason and resolves to {@link #OTHER}.
 */
public enum VerificationFailureReason {
    MISSING_HEADERS("missing-headers"),
    TIMESTAMP("timestamp"),
    NO_SECRET("no-secret"),
    SIGNATURE("signature"),
    /** A verification failure without a typed reason (e.g. from a host-supplied validator). */
    OTHER("other");

    private final String tag;

    VerificationFailureReason(String tag) {
        this.tag = tag;
    }

    /** The low-cardinality text recorded as the {@code reason} tag on the metric. */
    public String tag() {
        return tag;
    }

    /**
     * Resolve a verification exception to its reason: the typed reason this toolkit set at the throw site,
     * or {@link #OTHER} for any {@link WebhookVerificationException} we did not throw ourselves.
     */
    public static VerificationFailureReason from(WebhookVerificationException e) {
        return (e instanceof WebhookVerificationFailure failure) ? failure.reason() : OTHER;
    }
}
