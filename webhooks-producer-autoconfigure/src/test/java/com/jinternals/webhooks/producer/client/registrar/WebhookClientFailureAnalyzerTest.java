package com.jinternals.webhooks.producer.client.registrar;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class WebhookClientFailureAnalyzerTest {

    interface SampleClient {
        void publish(Object event);
    }

    private static Method sampleMethod() throws NoSuchMethodException {
        return SampleClient.class.getMethod("publish", Object.class);
    }

    @Test
    void rendersDescriptionAndActionFromTheException() throws Exception {
        InvalidWebhookClientException cause =
                new InvalidWebhookClientException(sampleMethod(), "must return void, but returns boolean.");

        FailureAnalysis analysis = new WebhookClientFailureAnalyzer().analyze(cause, cause);

        assertThat(analysis.getDescription())
                .isEqualTo(cause.getMessage())
                .contains("SampleClient#publish")
                .contains("must return void");
        assertThat(analysis.getAction())
                .contains("exactly one @WebhookType-annotated event argument")
                .contains("returns void")
                .contains("void publish(")
                .contains("fire-and-forget");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    void rendersATypeLevelActionWhenThereIsNoMethod() {
        // A type-level failure (no Method) must not NPE and must render the type-oriented action instead of
        // the method-signature one.
        InvalidWebhookClientException cause =
                new InvalidWebhookClientException("@WebhookClient Foo must be an interface.");

        FailureAnalysis analysis = new WebhookClientFailureAnalyzer().analyze(cause, cause);

        assertThat(analysis.getDescription()).isEqualTo(cause.getMessage()).contains("must be an interface");
        assertThat(analysis.getAction())
                .contains("Make the @WebhookClient an interface annotated with")
                .contains("non-blank destination")
                .doesNotContain("fire-and-forget");
        assertThat(analysis.getCause()).isSameAs(cause);
    }

    @Test
    void typeLevelExceptionHasANullMethod() {
        InvalidWebhookClientException cause = new InvalidWebhookClientException("some type problem");

        assertThat(cause.getMessage()).isEqualTo("some type problem");
        assertThat(cause.getMethod()).isNull();
    }

    @Test
    void exceptionMessageIncludesDeclaringTypeAndMethod() throws Exception {
        InvalidWebhookClientException cause = new InvalidWebhookClientException(sampleMethod(), "is bad.");

        assertThat(cause.getMessage()).isEqualTo("@WebhookClient method SampleClient#publish is bad.");
        assertThat(cause.getMethod()).isEqualTo(sampleMethod());
    }
}
