package com.jinternals.webhooks.sample.consumer.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.core.secret.SecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the consumer SPI examples documented in {@code docs/reference/beans-and-spis.md} compile,
 * replace the shipped {@code @ConditionalOnMissingBean} defaults cleanly, and obey their runtime contracts.
 */
@SpringBootTest
@ActiveProfiles("spi-examples")
class ConsumerSpiExamplesTest {

    @Autowired
    private IdempotencyGuard idempotencyGuard;

    @Autowired
    private SecretProvider<WebhookProperties.Endpoint> secretProvider;

    @Autowired
    private HandlerFailureListener handlerFailureListener;

    @Autowired
    private LoggingHandlerFailureListener loggingHandlerFailureListener;

    @Test
    void wiresCustomConsumerSpiBeansInPlaceOfDefaults() {
        assertThat(idempotencyGuard).isInstanceOf(SharedStoreIdempotencyGuard.class);
        assertThat(handlerFailureListener).isSameAs(loggingHandlerFailureListener);

        WebhookProperties.Endpoint crm = new WebhookProperties.Endpoint();
        crm.setPath("/webhooks/crm");
        assertThat(secretProvider.secretsFor(crm)).containsExactly("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

        WebhookProperties.Endpoint unknown = new WebhookProperties.Endpoint();
        unknown.setPath("/webhooks/unknown");
        assertThat(secretProvider.secretsFor(unknown)).isEmpty();
    }

    @Test
    void sharedStoreIdempotencyGuardFollowsReserveCommitAndReleaseContract() {
        assertThat(idempotencyGuard.tryBegin(null)).isTrue();
        assertThat(idempotencyGuard.tryBegin("   ")).isTrue();

        // Reserve -> duplicate while in flight -> release on failure -> retry succeeds -> commit suppresses
        assertThat(idempotencyGuard.tryBegin("msg_100")).isTrue();
        assertThat(idempotencyGuard.tryBegin("msg_100")).isFalse();

        idempotencyGuard.release("msg_100");
        assertThat(idempotencyGuard.tryBegin("msg_100")).isTrue();

        idempotencyGuard.markProcessed("msg_100");
        assertThat(idempotencyGuard.tryBegin("msg_100")).isFalse();
    }

    @Test
    void loggingHandlerFailureListenerCapturesFailuresAndTimeouts() {
        loggingHandlerFailureListener.clear();
        handlerFailureListener.onHandlerFailed("contact.created", new IllegalStateException("boom"));
        handlerFailureListener.onHandlerTimeout("invoice.paid");

        assertThat(loggingHandlerFailureListener.recordedFailures())
                .containsExactly(
                        new LoggingHandlerFailureListener.RecordedFailure("contact.created", "failed", "boom"),
                        new LoggingHandlerFailureListener.RecordedFailure("invoice.paid", "timeout", null));
    }
}
