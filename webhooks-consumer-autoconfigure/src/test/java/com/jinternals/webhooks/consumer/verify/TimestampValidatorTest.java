package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimestampValidatorTest {

    // A fixed "now" so the window boundaries are deterministic.
    private static final long NOW = 1_700_000_000L;
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final Duration TOLERANCE = Duration.ofMinutes(5); // 300s

    @Test
    void noOpAcceptsAnyTimestamp() {
        TimestampValidator noOp = new NoOpTimestampValidator();

        assertThatCode(() -> noOp.validate(0)).doesNotThrowAnyException();
        assertThatCode(() -> noOp.validate(NOW - 10_000_000)).doesNotThrowAnyException();
        assertThatCode(() -> noOp.validate(NOW + 10_000_000)).doesNotThrowAnyException();
    }

    @Test
    void clockSkewAcceptsAFreshTimestamp() {
        ClockSkewTimestampValidator window = new ClockSkewTimestampValidator(TOLERANCE, FIXED);

        assertThatCode(() -> window.validate(NOW - 10)).doesNotThrowAnyException();
        assertThatCode(() -> window.validate(NOW + 10)).doesNotThrowAnyException();
    }

    @Test
    void clockSkewAcceptsExactlyAtBothBounds() {
        ClockSkewTimestampValidator window = new ClockSkewTimestampValidator(TOLERANCE, FIXED);

        // Boundary is inclusive: now ± tolerance is still accepted.
        assertThatCode(() -> window.validate(NOW - 300)).doesNotThrowAnyException();
        assertThatCode(() -> window.validate(NOW + 300)).doesNotThrowAnyException();
    }

    @Test
    void clockSkewRejectsOneSecondTooOld() {
        ClockSkewTimestampValidator window = new ClockSkewTimestampValidator(TOLERANCE, FIXED);

        assertThatThrownBy(() -> window.validate(NOW - 301))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("too old");
    }

    @Test
    void clockSkewRejectsOneSecondTooNew() {
        ClockSkewTimestampValidator window = new ClockSkewTimestampValidator(TOLERANCE, FIXED);

        assertThatThrownBy(() -> window.validate(NOW + 301))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("too new");
    }

    @Test
    void publicConstructorUsesSystemClock() {
        // The system-clock constructor accepts a timestamp at "now" (within a generous tolerance),
        // covering the delegation to Clock.systemUTC() without pinning a value.
        ClockSkewTimestampValidator window = new ClockSkewTimestampValidator(Duration.ofHours(1));
        long now = System.currentTimeMillis() / 1000L;

        assertThatCode(() -> window.validate(now)).doesNotThrowAnyException();
    }
}
