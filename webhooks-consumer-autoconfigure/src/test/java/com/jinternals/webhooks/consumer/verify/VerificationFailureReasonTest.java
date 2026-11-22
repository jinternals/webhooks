package com.jinternals.webhooks.consumer.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VerificationFailureReasonTest {

    @ParameterizedTest
    @EnumSource(VerificationFailureReason.class)
    void readsTheTypedReasonBackFromTheFailure(VerificationFailureReason reason) {
        // A failure this toolkit threw carries its reason; from() reads it back directly (no text match).
        WebhookVerificationFailure failure = new WebhookVerificationFailure(reason, "message for " + reason);
        assertThat(VerificationFailureReason.from(failure)).isEqualTo(reason);
    }

    @Test
    void plainVerificationExceptionResolvesToOther() {
        // An exception we did NOT throw (e.g. a host-supplied TimestampValidator's) has no typed reason.
        WebhookVerificationException plain = new WebhookVerificationException("nope from a custom validator");
        assertThat(VerificationFailureReason.from(plain)).isEqualTo(VerificationFailureReason.OTHER);
    }

    @Test
    void plainExceptionWithCausePreservesMessageAndCauseAndResolvesToOther() {
        IllegalStateException cause = new IllegalStateException("root");
        WebhookVerificationException plain = new WebhookVerificationException("wrapped failure", cause);
        assertThat(plain).hasMessage("wrapped failure").hasCause(cause);
        assertThat(VerificationFailureReason.from(plain)).isEqualTo(VerificationFailureReason.OTHER);
    }

    @Test
    void plainExceptionWithNullMessageResolvesToOther() {
        WebhookVerificationException plain = new WebhookVerificationException(null);
        assertThat(VerificationFailureReason.from(plain)).isEqualTo(VerificationFailureReason.OTHER);
    }

    @Test
    void everyConstantHasATag() {
        for (VerificationFailureReason reason : VerificationFailureReason.values()) {
            assertThat(reason.tag()).isNotBlank();
        }
    }
}
