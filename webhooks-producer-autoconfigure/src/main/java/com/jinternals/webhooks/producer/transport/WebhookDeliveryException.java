package com.jinternals.webhooks.producer.transport;

import java.time.Duration;
import java.util.Optional;

/**
 * Thrown when an outbound webhook delivery fails — a non-2xx response from the destination or a
 * transport-level error (connection refused, timeout, etc.). The producer core surfaces this to the
 * caller rather than persisting; retry/backoff is an in-process, stateless concern layered on top (see
 * SPEC.md §19, §19b).
 *
 * <p>When the failure was an HTTP response, {@link #getStatusCode()} carries that status so a retry
 * policy can distinguish a <em>retryable</em> failure (5xx, 429) from a <em>fatal</em> one (4xx). A
 * transport-level error (no HTTP response at all) carries no status.
 *
 * <p>When the response carried a {@code Retry-After} header (commonly on {@code 429}/{@code 503}),
 * {@link #getRetryAfter()} exposes it so the retry policy can honor the server's requested delay as a
 * backoff floor (SPEC §20.6/A1). It is absent for transport errors and for responses without the header.
 */
public class WebhookDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The HTTP status of the failing response, or {@code 0} when the failure was transport-level. */
    private final int statusCode;

    /** The parsed {@code Retry-After} delay, or {@code null} when absent/unparseable. */
    private final Duration retryAfter;

    public WebhookDeliveryException(String message) {
        this(message, 0);
    }

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryAfter = null;
    }

    public WebhookDeliveryException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public WebhookDeliveryException(String message, int statusCode, Duration retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    /**
     * The HTTP status of the failing response, if any.
     *
     * @return the status code, or empty when the failure was transport-level (timeout, connection error)
     */
    public Optional<Integer> getStatusCode() {
        return statusCode == 0 ? Optional.empty() : Optional.of(statusCode);
    }

    /**
     * The server-requested retry delay parsed from the response's {@code Retry-After} header, if present.
     *
     * @return the delay, or empty when the response had no (parseable) {@code Retry-After} header
     */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
