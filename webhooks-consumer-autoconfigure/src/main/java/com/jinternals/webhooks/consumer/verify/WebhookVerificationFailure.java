package com.jinternals.webhooks.consumer.verify;

/**
 * A {@link WebhookVerificationException} thrown by this toolkit's own verification code that carries the
 * typed {@link VerificationFailureReason} alongside the human-readable message (see SPEC.md §8, §20.6/A4).
 *
 * <p>This exists so the metric {@code reason} tag is derived from a <strong>typed value set at the throw
 * site</strong>, not by substring-matching a free-text message downstream — rewording a message can no
 * longer silently change (or lose) the metric. It extends {@link WebhookVerificationException} so it still
 * flows through every {@code catch (WebhookVerificationException)} and the
 * {@code @ExceptionHandler(WebhookVerificationException.class)} mapping to a 400, unchanged.
 */
public class WebhookVerificationFailure extends WebhookVerificationException {

    private static final long serialVersionUID = 1L;

    private final VerificationFailureReason reason;

    public WebhookVerificationFailure(VerificationFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** The typed reason for this failure, used as the low-cardinality metric tag. */
    public VerificationFailureReason reason() {
        return reason;
    }
}
