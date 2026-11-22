package com.jinternals.webhooks.consumer.verify;

/**
 * Replay-window (timestamp freshness) policy for an inbound webhook (see SPEC.md §8b). This is the
 * <strong>extension point (SPI)</strong> for timestamp verification, factored out of
 * {@link WebhookVerifier} so the window is independently configurable, testable, and swappable.
 *
 * <p>The shipped implementations are {@link NoOpTimestampValidator} (default — the window is
 * <strong>disabled</strong>, any correctly-signed request is accepted regardless of age) and
 * {@link ClockSkewTimestampValidator} (opt-in — rejects a timestamp outside a configurable clock-skew
 * tolerance of the server clock, as Standard Webhooks prescribes).
 *
 * <p>Provide your own {@code @Bean TimestampValidator} to implement a different freshness policy; the
 * auto-configuration backs off when a bean is already present ({@code @ConditionalOnMissingBean}).
 *
 * <p><strong>Security note.</strong> The replay window is what lets a stateless consumer resist
 * captured-request replay attacks. With it disabled, an attacker who records one valid delivery can
 * re-send it indefinitely; enable it for internet-facing endpoints.
 */
public interface TimestampValidator {

    /**
     * Validate the webhook's timestamp (Unix epoch seconds). The timestamp has already been parsed from
     * the {@code webhook-timestamp} header and is the same value covered by the signature.
     *
     * @param timestampSeconds the webhook timestamp, in Unix epoch seconds
     * @throws WebhookVerificationException if the timestamp is outside the accepted freshness window
     */
    void validate(long timestampSeconds) throws WebhookVerificationException;
}
