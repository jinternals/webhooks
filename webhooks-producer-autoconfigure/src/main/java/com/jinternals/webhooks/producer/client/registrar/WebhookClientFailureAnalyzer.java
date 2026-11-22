package com.jinternals.webhooks.producer.client.registrar;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns an {@link InvalidWebhookClientException} into a clean Spring Boot startup failure — a focused
 * description plus a concrete action — instead of a raw stack trace, so a developer sees immediately
 * which {@code @WebhookClient} method is wrong and how to fix it (see SPEC.md §19).
 *
 * <p>Registered via {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}.
 */
public class WebhookClientFailureAnalyzer extends AbstractFailureAnalyzer<InvalidWebhookClientException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidWebhookClientException cause) {
        String description = cause.getMessage();
        return new FailureAnalysis(description, action(cause), cause);
    }

    private static String action(InvalidWebhookClientException cause) {
        if (cause.getMethod() == null) {
            // Type-level failure: not an interface, missing @WebhookClient, or a blank destination.
            return "Make the @WebhookClient an interface annotated with "
                    + "@WebhookClient(destination = \"...\") and give it a non-blank destination that maps to "
                    + "webhooks.producer.destinations.<destination> (url + secret).";
        }
        return "Change the method so it takes exactly one @WebhookType-annotated event argument and "
                + "returns void, e.g.  void " + cause.getMethod().getName()
                + "(MyEvent event);  — a webhook publish is fire-and-forget, so success is a normal "
                + "return and failure is a thrown WebhookDeliveryException (there is no value to return).";
    }
}
