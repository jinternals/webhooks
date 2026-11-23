package com.jinternals.webhooks.consumer.web.error;

import com.jinternals.webhooks.consumer.verify.WebhookVerificationException;
import com.jinternals.webhooks.consumer.web.WebhookReceiveController;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Uniform RFC-7807 {@link ProblemDetail} error responses (see SPEC.md §12). Adapted from
 * {@code dynamic-rest-api}'s {@code GlobalExceptionHandler}. The status code is the only signal the
 * sender reacts to (it drives their retries — STANDARD_WEBHOOKS.md §8).
 *
 * <p><strong>Scoped.</strong> This advice is bound to {@link WebhookReceiveController} via
 * {@code assignableTypes}, so in an existing Spring Boot application it only maps exceptions from the
 * webhook receive path — it never intercepts the host app's own controllers (see SPEC.md §14).
 */
@RestControllerAdvice(assignableTypes = WebhookReceiveController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bad signature / stale-or-future timestamp / missing headers → 400. Never log the secret. */
    @ExceptionHandler(WebhookVerificationException.class)
    public ProblemDetail onVerificationFailure(WebhookVerificationException ex) {
        log.warn("Webhook verification failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("urn:webhooks:verification-failed"));
        problem.setTitle("Webhook verification failed");
        return problem;
    }

    /** Payload failed Bean Validation against its {@code @WebhookType} class → 400 with an errors[] list. */
    @ExceptionHandler(PayloadValidationException.class)
    public ProblemDetail onPayloadValidation(PayloadValidationException ex) {
        log.warn("{}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Webhook payload failed validation");
        problem.setType(URI.create("urn:webhooks:payload-validation-failed"));
        problem.setTitle("Payload validation failed");
        problem.setProperty("eventType", ex.getType());
        problem.setProperty("errors", ex.getErrors());
        return problem;
    }

    /** Body exceeded the configured max size → 413 (rejected before verification). */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ProblemDetail onPayloadTooLarge(PayloadTooLargeException ex) {
        log.warn("{}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
        problem.setType(URI.create("urn:webhooks:payload-too-large"));
        problem.setTitle("Payload too large");
        problem.setProperty("limitBytes", ex.getLimitBytes());
        return problem;
    }

    /** No endpoint matched the path → 500 (routing/config issue, logged; never an NPE). */
    @ExceptionHandler(EndpointNotConfiguredException.class)
    public ProblemDetail onEndpointNotConfigured(EndpointNotConfiguredException ex) {
        log.error("{}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook endpoint not configured");
        problem.setType(URI.create("urn:webhooks:endpoint-not-configured"));
        problem.setTitle("Endpoint not configured");
        return problem;
    }

    /** Verified body could not be parsed as an envelope, or its data failed strict deserialization → 400. */
    @ExceptionHandler(InvalidPayloadException.class)
    public ProblemDetail onInvalidPayload(InvalidPayloadException ex) {
        log.warn("{}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("urn:webhooks:invalid-payload"));
        problem.setTitle("Invalid webhook payload");
        if (ex.getType() != null) {
            problem.setProperty("eventType", ex.getType());
        }
        return problem;
    }

    /** Unknown type under the REJECT policy → 400. */
    @ExceptionHandler(UnknownTypeException.class)
    public ProblemDetail onUnknownType(UnknownTypeException ex) {
        log.warn("{}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("urn:webhooks:unknown-type"));
        problem.setTitle("Unknown webhook type");
        problem.setProperty("eventType", ex.getType());
        problem.setProperty("path", ex.getPath());
        return problem;
    }

    /** A modelled, accepted type has no registered @WebhookHandler → 500 (config gap; sender retries). */
    @ExceptionHandler(NoWebhookHandlerException.class)
    public ProblemDetail onNoHandler(NoWebhookHandlerException ex) {
        log.error("{}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "No webhook handler registered");
        problem.setType(URI.create("urn:webhooks:no-handler"));
        problem.setTitle("No webhook handler");
        problem.setProperty("eventType", ex.getType());
        return problem;
    }

    /** A handler threw → 500 so the sender retries (keep handlers idempotent). */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        log.error("Webhook handling failed", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook handling failed");
        problem.setType(URI.create("urn:webhooks:handling-failed"));
        problem.setTitle("Webhook handling failed");
        return problem;
    }
}
