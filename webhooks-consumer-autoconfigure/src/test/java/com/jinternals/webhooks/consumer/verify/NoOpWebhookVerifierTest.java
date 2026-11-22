package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link NoOpWebhookVerifier}: it accepts every request unconditionally — no secret, no
 * signature header, no exception — which is the whole contract when verification is disabled.
 */
class NoOpWebhookVerifierTest {

    private final WebhookVerifier verifier = new NoOpWebhookVerifier();

    @Test
    void acceptsAnyRequestWithoutThrowing() {
        // Even with no secrets, no headers, and arbitrary body it must return normally. A mutant that made
        // verify() throw (or restored a guard) would fail here — this is what makes the endpoint open.
        assertThatCode(() -> verifier.verify(List.of(), null, "{}", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void ignoresNullArgumentsToo() {
        // The NoOp reads none of its arguments, so even nulls are accepted (it never dereferences them).
        assertThatCode(() -> verifier.verify(null, null, null, null)).doesNotThrowAnyException();
    }
}
