package com.jinternals.webhooks.producer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.client.registrar.InvalidWebhookClientException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Startup validation of {@code @WebhookClient} method signatures (SPEC.md §19). A client method must take
 * exactly one {@code @WebhookType}-annotated event argument and return {@code void}; a malformed client fails the
 * context eagerly at registration rather than at first call. Each case boots a tiny app and asserts the
 * refresh fails with the expected {@link IllegalStateException} message.
 */
class WebhookClientValidationTest {

    @WebhookType("contact.created")
    static class ContactCreated {}

    /** Boot the app and return the {@link InvalidWebhookClientException} found in the startup failure chain. */
    private static InvalidWebhookClientException startupError(Class<?> appClass) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(appClass)
                .web(WebApplicationType.NONE)
                .properties(
                        "webhooks.producer.destinations.crm.url=https://crm.example.com/webhooks",
                        "webhooks.producer.destinations.crm.secret=whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        Throwable failure = catchThrowable(() -> builder.run().close());
        assertThat(failure).as("context should fail to start").isNotNull();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InvalidWebhookClientException live) {
                return live;
            }
        }
        throw new AssertionError("No InvalidWebhookClientException in the failure chain", failure);
    }

    @WebhookClient(destination = "crm")
    interface NoArgClient {
        void publish();
    }

    @SpringBootApplication
    @EnableWebhookClients(clients = NoArgClient.class)
    static class NoArgApp {}

    @Test
    void rejectsZeroArgMethodAtStartup() {
        assertThat(startupError(NoArgApp.class))
                .hasMessageContaining("exactly one @WebhookType-annotated event argument");
    }

    @WebhookClient(destination = "crm")
    interface TwoArgClient {
        void publish(ContactCreated event, String extra);
    }

    @SpringBootApplication
    @EnableWebhookClients(clients = TwoArgClient.class)
    static class TwoArgApp {}

    @Test
    void rejectsMultiArgMethodAtStartup() {
        assertThat(startupError(TwoArgApp.class))
                .hasMessageContaining("exactly one @WebhookType-annotated event argument");
    }

    @WebhookClient(destination = "crm")
    interface UntypedArgClient {
        void publish(String notAnEvent);
    }

    @SpringBootApplication
    @EnableWebhookClients(clients = UntypedArgClient.class)
    static class UntypedArgApp {}

    @Test
    void rejectsNonTypeArgMethodAtStartup() {
        assertThat(startupError(UntypedArgApp.class)).hasMessageContaining("is not annotated with @WebhookType");
    }

    @WebhookClient(destination = "crm")
    interface NonVoidClient {
        boolean publish(ContactCreated event);
    }

    @SpringBootApplication
    @EnableWebhookClients(clients = NonVoidClient.class)
    static class NonVoidApp {}

    @Test
    void rejectsNonVoidReturnMethodAtStartup() {
        assertThat(startupError(NonVoidApp.class)).hasMessageContaining("must return void");
    }

    @SpringBootApplication
    @EnableWebhookClients(clients = com.jinternals.webhooks.producer.fixtures.invalidtype.NotAnInterfaceWebhooks.class)
    static class NotAnInterfaceApp {}

    @Test
    void rejectsANonInterfaceClientAtStartup() {
        assertThat(startupError(NotAnInterfaceApp.class)).hasMessageContaining("must be an interface");
    }

    @SpringBootApplication
    @EnableWebhookClients(
            clients = com.jinternals.webhooks.producer.fixtures.invalidtype.MissingAnnotationWebhooks.class)
    static class MissingAnnotationApp {}

    @Test
    void rejectsAMissingAnnotationClientAtStartup() {
        assertThat(startupError(MissingAnnotationApp.class))
                .hasMessageContaining("is missing the @WebhookClient annotation");
    }

    @SpringBootApplication
    @EnableWebhookClients(
            clients = com.jinternals.webhooks.producer.fixtures.invalidtype.BlankDestinationWebhooks.class)
    static class BlankDestinationApp {}

    @Test
    void rejectsABlankDestinationClientAtStartup() {
        assertThat(startupError(BlankDestinationApp.class))
                .hasMessageContaining("must declare a non-blank destination");
    }
}
