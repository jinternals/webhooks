package com.jinternals.webhooks.consumer.verify;

/**
 * Default {@link TimestampValidator}: the replay window is <strong>disabled</strong> (see SPEC.md §8b).
 * Any correctly-signed request is accepted regardless of its {@code webhook-timestamp} age — the
 * signature alone is trusted.
 *
 * <p>This is the shipped default. Switch on the window with
 * {@code webhooks.consumer.replay-window-enabled=true} (which wires {@link ClockSkewTimestampValidator})
 * or provide a custom {@code @Bean TimestampValidator}.
 */
public class NoOpTimestampValidator implements TimestampValidator {

    @Override
    public void validate(long timestampSeconds) {
        // No freshness check — accept any timestamp.
    }
}
