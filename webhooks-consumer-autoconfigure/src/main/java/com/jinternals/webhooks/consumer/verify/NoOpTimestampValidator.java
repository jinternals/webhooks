package com.jinternals.webhooks.consumer.verify;

/**
 * Opt-out {@link TimestampValidator}: the replay window is <strong>disabled</strong> (see SPEC.md §8b).
 * Any correctly-signed request is accepted regardless of its {@code webhook-timestamp} age — the
 * signature alone is trusted.
 *
 * <p>Wired when {@code webhooks.consumer.replay-window-enabled=false}; the shipped default is
 * {@link ClockSkewTimestampValidator}.
 */
public class NoOpTimestampValidator implements TimestampValidator {

    @Override
    public void validate(long timestampSeconds) {
        // No freshness check — accept any timestamp.
    }
}
