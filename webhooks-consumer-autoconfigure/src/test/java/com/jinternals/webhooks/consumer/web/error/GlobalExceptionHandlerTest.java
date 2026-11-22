package com.jinternals.webhooks.consumer.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.standardwebhooks.exceptions.WebhookVerificationException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void verificationFailureIs400() {
        ProblemDetail problem = handler.onVerificationFailure(new WebhookVerificationException("bad sig"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Webhook verification failed");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:verification-failed"));
        assertThat(problem.getDetail()).isEqualTo("bad sig");
    }

    @Test
    void payloadValidationIs400WithErrors() {
        ProblemDetail problem =
                handler.onPayloadValidation(new PayloadValidationException("contact.created", List.of("missing id")));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Payload validation failed");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:payload-validation-failed"));
        assertThat(problem.getDetail()).isEqualTo("Webhook payload failed validation");
        assertThat(problem.getProperties().get("errors")).isEqualTo(List.of("missing id"));
        assertThat(problem.getProperties().get("eventType")).isEqualTo("contact.created");
    }

    @Test
    void payloadTooLargeIs413() {
        ProblemDetail problem = handler.onPayloadTooLarge(new PayloadTooLargeException(256));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(problem.getTitle()).isEqualTo("Payload too large");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:payload-too-large"));
        assertThat(problem.getProperties().get("limitBytes")).isEqualTo(256L);
    }

    @Test
    void endpointNotConfiguredIs500() {
        ProblemDetail problem = handler.onEndpointNotConfigured(new EndpointNotConfiguredException("/w"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Endpoint not configured");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:endpoint-not-configured"));
        assertThat(problem.getDetail()).isEqualTo("Webhook endpoint not configured");
    }

    @Test
    void unknownTypeIs400WithTypeAndPath() {
        ProblemDetail problem =
                handler.onUnknownType(new UnknownTypeException("invoice.paid", "/webhooks/crm", "not allow-listed"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Unknown webhook type");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:unknown-type"));
        assertThat(problem.getProperties().get("eventType")).isEqualTo("invoice.paid");
        assertThat(problem.getProperties().get("path")).isEqualTo("/webhooks/crm");
    }

    @Test
    void invalidPayloadWithTypeIs400WithEventType() {
        ProblemDetail problem =
                handler.onInvalidPayload(new InvalidPayloadException("contact.created", "unknown field", null));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid webhook payload");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:invalid-payload"));
        assertThat(problem.getDetail()).contains("contact.created").contains("unknown field");
        assertThat(problem.getProperties().get("eventType")).isEqualTo("contact.created");
    }

    @Test
    void invalidPayloadWithoutTypeIs400WithoutEventType() {
        // The envelope-parse constructor leaves type null; the handler must omit the eventType property
        // (kills the mutant that drops the null guard and would NPE / always set it).
        ProblemDetail problem = handler.onInvalidPayload(new InvalidPayloadException("not json", null));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:invalid-payload"));
        // The eventType property KEY must be absent entirely, not merely mapped to null. (Spring's
        // setProperty(key, null) still inserts the key, so a dropped null-guard mutant would leave the
        // key present with a null value — asserting "no key" kills it.)
        assertThat(problem.getProperties() == null || !problem.getProperties().containsKey("eventType"))
                .isTrue();
    }

    @Test
    void noHandlerIs500WithEventType() {
        ProblemDetail problem =
                handler.onNoHandler(new NoWebhookHandlerException("contact.created", "com.example.ContactCreated"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("No webhook handler");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:no-handler"));
        assertThat(problem.getDetail()).isEqualTo("No webhook handler registered");
        assertThat(problem.getProperties().get("eventType")).isEqualTo("contact.created");
    }

    @Test
    void unexpectedIs500() {
        ProblemDetail problem = handler.onUnexpected(new RuntimeException("boom"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Webhook handling failed");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:webhooks:handling-failed"));
        assertThat(problem.getDetail()).isEqualTo("Webhook handling failed");
    }
}
