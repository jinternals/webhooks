package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Retry;
import com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import com.jinternals.webhooks.producer.support.LogCapture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryingWebhookTransportTest {

    private static final String URL = "https://crm.example.com/webhooks";
    private static final Map<String, List<String>> HEADERS = Map.of("webhook-id", List.of("msg_1"));
    private static final String BODY = "{\"a\":1}";

    private final DestinationUrlValidator allowAll = new AllowAllDestinationUrlValidator();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<DeliveryFailure> deadLetters = new ArrayList<>();
    private final WebhookDeliveryListener recording = deadLetters::add;

    private Retry retry(int maxAttempts) {
        Retry r = new Retry();
        r.setMaxAttempts(maxAttempts);
        r.setInitialBackoff(Duration.ofMillis(100));
        r.setMaxBackoff(Duration.ofMillis(1000));
        r.setJitter(0.0); // deterministic backoff for assertions
        return r;
    }

    /** A transport that fails a scripted number of times with the given exception, then succeeds. */
    private WebhookTransport failing(int failures, WebhookDeliveryException ex, AtomicInteger calls) {
        return (url, headers, body) -> {
            int n = calls.incrementAndGet();
            if (n <= failures) {
                throw ex;
            }
        };
    }

    private RetryingWebhookTransport retrying(WebhookTransport delegate, Retry retry) {
        // jitter fixed to 0 via retry(), so the random supplier value is irrelevant; record sleeps +
        // any dead-lettered failures.
        return new RetryingWebhookTransport(delegate, allowAll, recording, retry, () -> 0.0, sleeps::add);
    }

    @Test
    void succeedsOnFirstAttemptWithoutSleeping() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(0, new WebhookDeliveryException("x", 500), calls);

        retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesA5xxThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(2, new WebhookDeliveryException("x", 503), calls);

        retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(3);
        // Two backoffs: 100ms then 200ms (exponential, jitter 0).
        assertThat(sleeps).containsExactly(100L, 200L);
    }

    @Test
    void retriesA429() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("rate limited", 429), calls);

        retrying(delegate, retry(2)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retriesATransportErrorWithNoStatus() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("timeout", new RuntimeException()), calls);

        retrying(delegate, retry(2)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void honorsRetryAfterAsABackoffFloorWhenLargerThanTheExponentialBackoff() {
        // 503 + Retry-After: 500ms. Exponential would be 100ms on attempt 1; the server-requested 500ms
        // is larger (and under the 1000ms max-backoff), so it becomes the wait (SPEC §20.6/A1).
        AtomicInteger calls = new AtomicInteger();
        WebhookDeliveryException ex = new WebhookDeliveryException("slow down", 503, Duration.ofMillis(500));
        WebhookTransport delegate = failing(1, ex, calls);

        retrying(delegate, retry(2)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(500L); // Retry-After floor, not the 100ms exponential
    }

    @Test
    void ignoresRetryAfterWhenSmallerThanTheExponentialBackoff() {
        // 503 + Retry-After: 10ms on attempt 2, where exponential is already 200ms. The larger wins.
        AtomicInteger calls = new AtomicInteger();
        WebhookDeliveryException ex = new WebhookDeliveryException("slow down", 503, Duration.ofMillis(10));
        WebhookTransport delegate = failing(2, ex, calls);

        retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(3);
        // attempt 1: max(100, 10)=100; attempt 2: max(200, 10)=200.
        assertThat(sleeps).containsExactly(100L, 200L);
    }

    @Test
    void clampsRetryAfterToTheMaxBackoff() {
        // Retry-After: 1h, but max-backoff is 1000ms (see retry()); the wait is clamped to the cap.
        AtomicInteger calls = new AtomicInteger();
        WebhookDeliveryException ex = new WebhookDeliveryException("slow down", 503, Duration.ofHours(1));
        WebhookTransport delegate = failing(1, ex, calls);

        retrying(delegate, retry(2)).send(URL, HEADERS, BODY);

        assertThat(sleeps).containsExactly(1000L); // clamped to max-backoff, not an hour
    }

    @Test
    void doesNotRetryA4xxAndFailsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("bad request", 400), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(3)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("bad request");

        assertThat(calls.get()).isEqualTo(1); // no retry on a client error
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rethrowsLastExceptionAfterExhaustingAttempts() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("still down", 500), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(3)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("still down");

        assertThat(calls.get()).isEqualTo(3);
        assertThat(sleeps).hasSize(2); // slept before attempts 2 and 3, not after the last
    }

    @Test
    void maxAttemptsOfOneDisablesRetry() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("down", 500), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(1)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void maxAttemptsBelowOneIsClampedToOne() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("down", 500), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(0)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(5);
        r.setInitialBackoff(Duration.ofMillis(400));
        r.setMaxBackoff(Duration.ofMillis(1000)); // 400, 800, 1000(capped), 1000(capped)

        assertThatThrownBy(() -> retrying(delegate, r).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(sleeps).containsExactly(400L, 800L, 1000L, 1000L);
    }

    @Test
    void jitterReducesTheBackoffWithinBounds() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(2);
        r.setJitter(0.5);
        // random supplier returns 1.0 -> factor = 1 - 0.5*1.0 = 0.5 -> 100ms * 0.5 = 50ms.
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, allowAll, recording, r, () -> 1.0, sleeps::add);

        transport.send(URL, HEADERS, BODY);

        assertThat(sleeps).containsExactly(50L);
    }

    @Test
    void clampsANegativeInitialBackoffToZero() {
        // A misconfigured negative initial-backoff must never reach Thread.sleep (which would throw
        // IllegalArgumentException on a negative). It is clamped to 0 so the retry still proceeds.
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(2);
        r.setInitialBackoff(Duration.ofMillis(-100));
        r.setMaxBackoff(Duration.ofMillis(-50)); // also negative; clamped up to the (floored) initial

        retrying(delegate, r).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(0L); // clamped, not negative
    }

    @Test
    void clampsAnOutOfRangeJitterIntoZeroToOne() {
        // A jitter above 1.0 would make the jitter factor (1 - jitter*random) negative and produce a
        // negative backoff. It must be clamped to 1.0 (full jitter). With random=1.0 the factor is exactly
        // 0, so the backoff floors at 0 rather than going negative.
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(2);
        r.setJitter(5.0); // out of range -> clamped to 1.0
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, allowAll, recording, r, () -> 1.0, sleeps::add);

        transport.send(URL, HEADERS, BODY);

        // 100ms * (1 - 1.0*1.0) = 0ms. Without the clamp this would be 100 * (1 - 5) = -400ms.
        assertThat(sleeps).containsExactly(0L);
    }

    @Test
    void clampsANegativeJitterToZero() {
        // A negative jitter would increase the backoff above the capped value (factor > 1). It must be
        // clamped to 0 (no jitter), so the backoff is exactly the deterministic exponential value.
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(2);
        r.setJitter(-1.0); // out of range -> clamped to 0.0
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, allowAll, recording, r, () -> 1.0, sleeps::add);

        transport.send(URL, HEADERS, BODY);

        // factor = 1 - 0.0*1.0 = 1.0 -> 100ms * 1.0 = 100ms (no inflation from the negative jitter).
        assertThat(sleeps).containsExactly(100L);
    }

    @Test
    void validatesTheUrlOnceBeforeAttempting() {
        AtomicInteger validatorCalls = new AtomicInteger();
        AtomicInteger sendCalls = new AtomicInteger();
        DestinationUrlValidator rejecting = url -> {
            validatorCalls.incrementAndGet();
            throw new WebhookDeliveryException("Destination host not allowed: " + url);
        };
        WebhookTransport delegate = (url, headers, body) -> sendCalls.incrementAndGet();
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, rejecting, recording, retry(3), () -> 0.0, sleeps::add);

        assertThatThrownBy(() -> transport.send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("not allowed");

        assertThat(validatorCalls.get()).isEqualTo(1); // validated once, before the loop
        assertThat(sendCalls.get()).isZero(); // never attempted the delegate
        assertThat(sleeps).isEmpty();
        // A disallowed URL is a pre-attempt security/config error, not a dead-lettered delivery.
        assertThat(deadLetters).isEmpty();
    }

    @Test
    void notifiesTheListenerWhenAttemptsAreExhausted() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("still down", 500), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(3)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(deadLetters).hasSize(1);
        DeliveryFailure failure = deadLetters.get(0);
        assertThat(failure.url()).isEqualTo(URL);
        assertThat(failure.headers()).isEqualTo(HEADERS);
        assertThat(failure.body()).isEqualTo(BODY);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.reason()).isEqualTo(DeliveryFailure.Reason.EXHAUSTED);
        assertThat(failure.cause().getStatusCode()).contains(500);
        assertThat(failure.cause().getMessage()).isEqualTo("still down");
    }

    @Test
    void notifiesTheListenerOnAFatalFourxxWithASingleAttempt() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("bad request", 400), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(3)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).attempts()).isEqualTo(1); // never retried
        assertThat(deadLetters.get(0).reason()).isEqualTo(DeliveryFailure.Reason.FATAL);
        assertThat(deadLetters.get(0).cause().getStatusCode()).contains(400);
    }

    @Test
    void notifiesTheListenerOnAFatalFourxxThatLandsOnTheFinalAttempt() {
        // maxAttempts=1 + a fatal 4xx: attempts(1) == maxAttempts(1) is TRUE, but isRetryable(400) is
        // FALSE, so the delivery is abandoned as "fatal", not "retries exhausted". Covers the second
        // operand of the `attempts == maxAttempts && isRetryable(cause)` branch in abandon().
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("bad request", 400), calls);

        assertThatThrownBy(() -> retrying(delegate, retry(1)).send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).attempts()).isEqualTo(1);
        assertThat(deadLetters.get(0).reason()).isEqualTo(DeliveryFailure.Reason.FATAL);
        assertThat(deadLetters.get(0).cause().getStatusCode()).contains(400);
    }

    @Test
    void doesNotNotifyTheListenerOnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);

        retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2); // one retry then success
        assertThat(deadLetters).isEmpty(); // a recovered delivery is not dead-lettered
    }

    @Test
    void aThrowingListenerIsIsolatedAndTheOriginalFailureStillPropagates() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("still down", 500), calls);
        WebhookDeliveryListener throwing = f -> {
            throw new IllegalStateException("listener boom");
        };
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, allowAll, throwing, retry(2), () -> 0.0, sleeps::add);

        // The listener's exception must not mask the real delivery failure.
        assertThatThrownBy(() -> transport.send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("still down");
    }

    @Test
    void logsAnInfoLineOnlyWhenADeliverySucceedsAfterARetry() {
        try (LogCapture logs = LogCapture.forClass(RetryingWebhookTransport.class)) {
            AtomicInteger calls = new AtomicInteger();
            WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 503), calls);

            retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

            // One WARN (the failed attempt 1) + one INFO (recovered on attempt 2). The INFO line names
            // the attempt — kills the `attempt > 1` boundary/conditional mutants, which only affect this log.
            assertThat(logs.formattedMessages()).anyMatch(m -> m.contains("succeeded on attempt 2/3"));
        }
    }

    @Test
    void doesNotLogASuccessLineOnAFirstAttemptSuccess() {
        try (LogCapture logs = LogCapture.forClass(RetryingWebhookTransport.class)) {
            AtomicInteger calls = new AtomicInteger();
            WebhookTransport delegate = failing(0, new WebhookDeliveryException("x", 500), calls);

            retrying(delegate, retry(3)).send(URL, HEADERS, BODY);

            // attempt == 1 -> the `attempt > 1` guard is false -> no "succeeded on attempt" line at all.
            assertThat(logs.formattedMessages()).noneMatch(m -> m.contains("succeeded on attempt"));
        }
    }

    @Test
    void logsExhaustedVersusFatalAndTheStatusTextWhenAbandoning() {
        // Exhausted (retryable, out of attempts): the ERROR line says "retries exhausted" + the HTTP status.
        try (LogCapture logs = LogCapture.forClass(RetryingWebhookTransport.class)) {
            AtomicInteger calls = new AtomicInteger();
            WebhookTransport delegate = failing(99, new WebhookDeliveryException("still down", 500), calls);

            assertThatThrownBy(() -> retrying(delegate, retry(2)).send(URL, HEADERS, BODY))
                    .isInstanceOf(WebhookDeliveryException.class);

            // Pins the L125 ternary ("retries exhausted" vs "fatal") and L138 statusText ("500").
            assertThat(logs.lastFormattedMessage())
                    .contains("abandoned")
                    .contains("retries exhausted")
                    .contains("status=500")
                    .doesNotContain("[fatal]");
        }
    }

    @Test
    void logsFatalAndTheStatusTextForANonRetryableFourxx() {
        // A fatal (non-retryable) 4xx: the ERROR line says "[fatal]" and carries the HTTP status.
        // (A transport error has no status and IS retryable, so "fatal" always implies a status is present.)
        try (LogCapture logs = LogCapture.forClass(RetryingWebhookTransport.class)) {
            AtomicInteger calls = new AtomicInteger();
            WebhookTransport delegate = failing(99, new WebhookDeliveryException("bad", 400), calls);

            assertThatThrownBy(() -> retrying(delegate, retry(3)).send(URL, HEADERS, BODY))
                    .isInstanceOf(WebhookDeliveryException.class);

            assertThat(logs.lastFormattedMessage())
                    .contains("abandoned")
                    .contains("[fatal]")
                    .contains("status=400")
                    .doesNotContain("retries exhausted");
        }
    }

    @Test
    void logsTransportStatusTextWhenAbandoningATransportError() {
        // A transport error (no HTTP status) exhausted over its attempts: statusText must render "transport",
        // killing the L138 "replace return with empty string" mutant on the no-status branch.
        try (LogCapture logs = LogCapture.forClass(RetryingWebhookTransport.class)) {
            AtomicInteger calls = new AtomicInteger();
            WebhookTransport delegate =
                    failing(99, new WebhookDeliveryException("timeout", new RuntimeException()), calls);

            assertThatThrownBy(() -> retrying(delegate, retry(2)).send(URL, HEADERS, BODY))
                    .isInstanceOf(WebhookDeliveryException.class);

            assertThat(logs.lastFormattedMessage()).contains("abandoned").contains("status=transport");
        }
    }

    @Test
    void defaultsToANoOpListenerViaTheThreeArgConstructor() {
        // The 3-arg public constructor wires NoOpWebhookDeliveryListener: an abandoned delivery is simply
        // rethrown, with no listener wired and nothing captured.
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(1); // no sleeping

        RetryingWebhookTransport transport = new RetryingWebhookTransport(delegate, allowAll, r);

        assertThatThrownBy(() -> transport.send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("down");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void interruptedSleepIsSurfacedAndRestoresTheInterruptFlag() {
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(99, new WebhookDeliveryException("down", 500), calls);
        RetryingWebhookTransport.Sleeper interrupting = millis -> {
            throw new InterruptedException("interrupted");
        };
        RetryingWebhookTransport transport =
                new RetryingWebhookTransport(delegate, allowAll, recording, retry(3), () -> 0.0, interrupting);

        assertThatThrownBy(() -> transport.send(URL, HEADERS, BODY))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("retry interrupted");
        assertThat(Thread.interrupted()).isTrue(); // flag restored (and cleared by this check)
    }

    @Test
    void publicConstructorRetriesThroughTheRealSeams() {
        // Exercise the production three-arg constructor (default random + Thread.sleep seams) end-to-end.
        // A 1ms backoff keeps the one real sleep negligible; the delegate fails once then succeeds.
        AtomicInteger calls = new AtomicInteger();
        WebhookTransport delegate = failing(1, new WebhookDeliveryException("down", 500), calls);
        Retry r = retry(2);
        r.setInitialBackoff(Duration.ofMillis(1));
        r.setMaxBackoff(Duration.ofMillis(1));

        new RetryingWebhookTransport(delegate, allowAll, r).send(URL, HEADERS, BODY);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void defaultRandomSeamDrawsRealVariingValues() {
        // The production random seam must consult the PRNG, not return a constant. A real draw yields a
        // value in [0, 1) that varies; this kills the "replace return with 0.0d" mutant (which would make
        // every draw exactly 0.0).
        boolean sawNonZero = false;
        for (int i = 0; i < 1000 && !sawNonZero; i++) {
            double v = RetryingWebhookTransport.THREAD_LOCAL_RANDOM.getAsDouble();
            assertThat(v).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
            if (v > 0.0) {
                sawNonZero = true;
            }
        }
        assertThat(sawNonZero).as("real PRNG must produce a non-zero draw").isTrue();
    }

    @Test
    void defaultSleepSeamActuallySleepsAndIsInterruptible() {
        // The production sleep seam must call Thread.sleep. With the interrupt flag set, a real
        // Thread.sleep throws InterruptedException immediately; the "remove call to Thread.sleep" mutant
        // would throw nothing. (Also keeps the test fast — no real waiting.)
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> RetryingWebhookTransport.THREAD_SLEEP.sleep(60_000))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            // Clear any lingering interrupt flag so it cannot leak into other tests.
            Thread.interrupted();
        }
    }
}
