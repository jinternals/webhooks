package com.jinternals.webhooks.consumer.verify;

import java.time.Clock;
import java.time.Duration;

/**
 * Opt-in {@link TimestampValidator} that enforces the Standard Webhooks replay window (see SPEC.md §8b):
 * a request is accepted only when its timestamp is within {@code tolerance} of the server clock in
 * either direction, i.e. {@code |now - timestamp| <= tolerance}. Anything older is rejected as
 * {@code "Message timestamp too old"} and anything further in the future as
 * {@code "Message timestamp too new"}.
 *
 * <p>Wired when {@code webhooks.consumer.replay-window-enabled=true}; the tolerance comes from
 * {@code webhooks.consumer.replay-tolerance} (default 5 minutes).
 */
public class ClockSkewTimestampValidator implements TimestampValidator {

    private final long toleranceSeconds;
    private final Clock clock;

    /**
     * @param tolerance allowed clock skew in either direction
     */
    public ClockSkewTimestampValidator(Duration tolerance) {
        this(tolerance, Clock.systemUTC());
    }

    /**
     * Package-visible constructor taking an explicit {@link Clock} so tests can pin "now" deterministically.
     */
    ClockSkewTimestampValidator(Duration tolerance, Clock clock) {
        this.toleranceSeconds = tolerance.getSeconds();
        this.clock = clock;
    }

    @Override
    public void validate(long timestampSeconds) throws WebhookVerificationException {
        long now = clock.millis() / 1000L;
        if (timestampSeconds < saturatingSubtract(now, toleranceSeconds)) {
            throw new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "Message timestamp too old");
        }
        if (timestampSeconds > saturatingAdd(now, toleranceSeconds)) {
            throw new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "Message timestamp too new");
        }
    }

    private static long saturatingSubtract(long a, long b) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }

    private static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
