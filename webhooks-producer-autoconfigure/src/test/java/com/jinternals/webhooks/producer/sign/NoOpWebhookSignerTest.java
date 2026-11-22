package com.jinternals.webhooks.producer.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link NoOpWebhookSigner}: it reports signing disabled (so the publisher omits the
 * {@code webhook-signature} header and requires no secret) and refuses to produce a signature if ever called.
 */
class NoOpWebhookSignerTest {

    private final NoOpWebhookSigner signer = new NoOpWebhookSigner();

    @Test
    void reportsSigningDisabled() {
        // isEnabled() == false is the whole contract: it is what makes the publisher skip signing. A mutant
        // flipping this to true would make the publisher demand a secret and sign — this kills it.
        assertThat(signer.isEnabled()).isFalse();
    }

    @Test
    void signThrowsBecauseItMustNeverBePutOnTheWire() {
        // sign(...) is unreachable in normal flow; if something calls it anyway it must fail loudly rather
        // than return a bogus value that could be sent as a real signature.
        assertThatThrownBy(() -> signer.sign("whsec_x", null, "msg_1", 1L, "{}"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Signing is disabled");
    }
}
