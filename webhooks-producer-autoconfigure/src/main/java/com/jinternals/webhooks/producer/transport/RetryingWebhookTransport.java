package com.jinternals.webhooks.producer.transport;

import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Retry;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WebhookTransport} decorator adding <strong>in-process, stateless</strong> retry with
 * exponential backoff and jitter (see SPEC.md §19b). It wraps a delegate transport (by default
 * {@link RestClientWebhookTransport}) and re-invokes it on <em>retryable</em> failures only:
 *
 * <ul>
 *   <li>a {@code 5xx} response or {@code 429 Too Many Requests};</li>
 *   <li>a transport-level error (timeout, connection refused) — a {@link WebhookDeliveryException} with
 *       no HTTP status.</li>
 * </ul>
 *
 * <p>A {@code 4xx} (other than {@code 429}) is <strong>fatal</strong> and fails immediately — retrying a
 * client error would never succeed. After {@code max-attempts} the last exception is rethrown. There is
 * no queue, disk, or scheduler: the backoff is a bounded {@link Thread#sleep} on the calling thread, so
 * the no-persistence rule is preserved.
 *
 * <p>When a delivery is finally abandoned (fatal {@code 4xx} or attempts exhausted), a
 * {@link WebhookDeliveryListener} is notified with a {@link DeliveryFailure} <em>before</em> the exception
 * is rethrown — the dead-letter hook (SPEC §20). The callback is isolated: a listener that throws is logged
 * and cannot change the caller-visible outcome.
 */
public class RetryingWebhookTransport implements AttemptAwareWebhookTransport {

    private static final Logger log = LoggerFactory.getLogger(RetryingWebhookTransport.class);
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * Production randomness seam: a uniform draw in {@code [0, 1)} from the per-thread PRNG, used to
     * jitter each backoff. Package-visible (not an inline lambda) so it is a first-class, directly
     * unit-testable default rather than hidden behind the public constructor.
     */
    static final DoubleSupplier THREAD_LOCAL_RANDOM =
            () -> ThreadLocalRandom.current().nextDouble();

    /**
     * Production sleep seam: a real {@link Thread#sleep}. Package-visible for the same reason as
     * {@link #THREAD_LOCAL_RANDOM} — so a test can prove it actually sleeps (and propagates interruption).
     */
    static final Sleeper THREAD_SLEEP = Thread::sleep;

    private final WebhookTransport delegate;
    private final DestinationUrlValidator urlValidator;
    private final WebhookDeliveryListener deliveryListener;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final double jitter;
    private final DoubleSupplier random;
    private final Sleeper sleeper;

    public RetryingWebhookTransport(WebhookTransport delegate, DestinationUrlValidator urlValidator, Retry retry) {
        this(delegate, urlValidator, new NoOpWebhookDeliveryListener(), retry);
    }

    public RetryingWebhookTransport(
            WebhookTransport delegate,
            DestinationUrlValidator urlValidator,
            WebhookDeliveryListener deliveryListener,
            Retry retry) {
        this(delegate, urlValidator, deliveryListener, retry, THREAD_LOCAL_RANDOM, THREAD_SLEEP);
    }

    /** Package-visible constructor injecting the randomness and sleep seams so tests are deterministic. */
    RetryingWebhookTransport(
            WebhookTransport delegate,
            DestinationUrlValidator urlValidator,
            WebhookDeliveryListener deliveryListener,
            Retry retry,
            DoubleSupplier random,
            Sleeper sleeper) {
        this.delegate = delegate;
        this.urlValidator = urlValidator;
        this.deliveryListener = deliveryListener;
        this.maxAttempts = Math.max(1, retry.getMaxAttempts());
        // Clamp backoffs to a sane floor: a negative configured Duration would otherwise yield a negative
        // sleep (IllegalArgumentException from Thread.sleep). initial is floored at 0, max at the
        // (already-floored) initial so max >= initial always holds and the cap can never invert.
        this.initialBackoffMillis = Math.max(0L, saturatedMillis(retry.getInitialBackoff()));
        this.maxBackoffMillis = Math.max(initialBackoffMillis, saturatedMillis(retry.getMaxBackoff()));
        // Clamp jitter into [0, 1] (and treat NaN as 0.0): out-of-range config (e.g. 2.0) would make the
        // jitter factor negative and produce a negative backoff. 0 disables jitter, 1 is full jitter.
        double rawJitter = retry.getJitter();
        this.jitter = Double.isNaN(rawJitter) ? 0.0 : Math.min(1.0, Math.max(0.0, rawJitter));
        this.random = random;
        this.sleeper = sleeper;
    }

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        sendPerAttempt(url, () -> headers, body);
    }

    @Override
    public void sendPerAttempt(String url, Supplier<Map<String, List<String>>> headersSupplier, String body) {
        // Validate the destination once, before the loop: a disallowed URL is fatal, never retried.
        urlValidator.validate(url);
        // Bounded loop: every iteration either returns (success) or throws (fatal / attempts exhausted),
        // so there is no fall-through exit and no dead "unreachable throw" to cover.
        for (int attempt = 1; ; attempt++) {
            Map<String, List<String>> headers = headersSupplier.get();
            try {
                delegate.send(url, headers, body);
                if (attempt > 1) {
                    log.info(
                            "Webhook delivery to {} succeeded on attempt {}/{}",
                            SafeUrl.sanitize(url),
                            attempt,
                            maxAttempts);
                }
                return;
            } catch (WebhookDeliveryException e) {
                if (attempt == maxAttempts || !isRetryable(e)) {
                    abandon(url, headers, body, attempt, e);
                    throw e;
                }
                long backoff = backoffWithRetryAfter(attempt, e);
                log.warn(
                        "Webhook delivery to {} failed (attempt {}/{}, status={}), retrying in {}ms: {}",
                        SafeUrl.sanitize(url),
                        attempt,
                        maxAttempts,
                        statusText(e),
                        backoff,
                        SafeUrl.redactMessage(e.getMessage(), url));
                sleepOrAbandon(url, headers, body, attempt, backoff, e);
            }
        }
    }

    /**
     * A delivery has been given up (fatal {@code 4xx} or attempts exhausted): log at {@code ERROR} and
     * notify the dead-letter listener before the exception is rethrown. The listener is isolated — one that
     * throws is logged and cannot change the caller-visible outcome.
     */
    private void abandon(
            String url, Map<String, List<String>> headers, String body, int attempts, WebhookDeliveryException cause) {
        DeliveryFailure.Reason reason =
                isRetryable(cause) ? DeliveryFailure.Reason.EXHAUSTED : DeliveryFailure.Reason.FATAL;
        log.error(
                "Webhook delivery to {} abandoned after {} attempt(s) [{}], status={}: {}",
                SafeUrl.sanitize(url),
                attempts,
                reason == DeliveryFailure.Reason.EXHAUSTED ? "retries exhausted" : "fatal",
                statusText(cause),
                SafeUrl.redactMessage(cause.getMessage(), url));
        try {
            deliveryListener.onDeliveryFailed(new DeliveryFailure(url, headers, body, attempts, reason, cause));
        } catch (RuntimeException listenerError) {
            log.error(
                    "WebhookDeliveryListener threw while handling abandoned delivery to {} "
                            + "(the original delivery failure is still propagated to the caller)",
                    SafeUrl.sanitize(url),
                    listenerError);
        }
    }

    /** Human-readable status for logs: the HTTP code, or {@code "transport"} when there was no response. */
    private static String statusText(WebhookDeliveryException e) {
        return e.getStatusCode().map(String::valueOf).orElse("transport");
    }

    /** 5xx and 429 are retryable; a transport error (no status) is retryable; any other 4xx is fatal. */
    private boolean isRetryable(WebhookDeliveryException e) {
        Optional<Integer> status = e.getStatusCode();
        if (status.isEmpty()) {
            return true; // transport-level error (timeout, connection refused)
        }
        int code = status.get();
        return code >= 500 || code == TOO_MANY_REQUESTS;
    }

    /**
     * The backoff before the next attempt: the server's {@code Retry-After} (if present) acts as a floor,
     * so the wait is {@code max(exponential-backoff, Retry-After)}, both clamped to {@code max-backoff}
     * (SPEC §20.6/A1). Honoring a server's requested delay avoids hammering a destination that has
     * explicitly asked for a pause (e.g. a {@code 429}/{@code 503}).
     */
    private long backoffWithRetryAfter(int attempt, WebhookDeliveryException e) {
        long backoff = backoffForAttempt(attempt);
        Optional<Duration> retryAfter = e.getRetryAfter();
        if (retryAfter.isPresent()) {
            return Math.max(backoff, cappedRetryAfterMillis(retryAfter.get()));
        }
        return backoff;
    }

    private long cappedRetryAfterMillis(Duration retryAfter) {
        return Math.min(saturatedMillis(retryAfter), maxBackoffMillis);
    }

    private static long saturatedMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Exponential backoff (initial * 2^(attempt-1)), capped at maxBackoff, then jitter applied. */
    private long backoffForAttempt(int attempt) {
        long exponential = initialBackoffMillis;
        for (int i = 1; i < attempt; i++) {
            exponential = Math.min(maxBackoffMillis, exponential + Math.min(exponential, Long.MAX_VALUE - exponential));
        }
        // Jitter: sample from [exponential*(1-jitter), exponential].
        double factor = 1.0 - (jitter * random.getAsDouble());
        return (long) (exponential * factor);
    }

    private void sleepOrAbandon(
            String url,
            Map<String, List<String>> headers,
            String body,
            int attempt,
            long millis,
            WebhookDeliveryException previousFailure) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WebhookDeliveryException interrupted =
                    new WebhookDeliveryException("Webhook delivery retry interrupted", e);
            interrupted.addSuppressed(previousFailure);
            abandon(url, headers, body, attempt, interrupted);
            throw interrupted;
        }
    }

    /** Seam over {@link Thread#sleep} so tests don't actually wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
