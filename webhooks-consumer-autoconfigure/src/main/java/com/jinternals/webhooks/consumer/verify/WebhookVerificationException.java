package com.jinternals.webhooks.consumer.verify;

/**
 * Thrown when an inbound webhook fails authenticity or freshness verification — missing required headers,
 * malformed or stale/future {@code webhook-timestamp}, missing endpoint secret, or no matching signature in
 * {@code webhook-signature} (see SPEC.md §8, §8b, §12).
 *
 * <p>Owned by this library so public consumer SPIs ({@link WebhookVerifier}, {@link TimestampValidator}) do
 * not leak third-party exception types from underlying cryptographic libraries, and unchecked
 * ({@link RuntimeException}) to match every other pipeline exception in the toolkit (and
 * {@code WebhookDeliveryException} on the producer side). Mapped to {@code 400 Bad Request}
 * ({@code urn:webhooks:verification-failed}) by
 * {@link com.jinternals.webhooks.consumer.web.error.GlobalExceptionHandler}.
 *
 * <p>Verification failures thrown by the toolkit itself use the {@link WebhookVerificationFailure} subclass
 * so a low-cardinality {@link VerificationFailureReason} is carried directly to metrics without string
 * matching; custom {@link WebhookVerifier} or {@link TimestampValidator} beans may throw either this base
 * exception (metered as {@link VerificationFailureReason#OTHER}) or {@link WebhookVerificationFailure}.
 */
public class WebhookVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebhookVerificationException(String message) {
        super(message);
    }

    public WebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
