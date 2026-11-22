package com.jinternals.webhooks.consumer.dispatch;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class NoOpHandlerFailureListenerTest {

    private final HandlerFailureListener listener = new NoOpHandlerFailureListener();

    @Test
    void onHandlerFailedDoesNothingAndNeverThrows() {
        assertThatCode(() -> listener.onHandlerFailed("contact.created", new IllegalStateException("x")))
                .doesNotThrowAnyException();
    }

    @Test
    void onHandlerTimeoutDoesNothingAndNeverThrows() {
        assertThatCode(() -> listener.onHandlerTimeout("contact.created")).doesNotThrowAnyException();
    }
}
