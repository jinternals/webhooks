package com.jinternals.webhooks.consumer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.consumer.config.WebhookProperties.UnknownTypePolicy;
import com.jinternals.webhooks.consumer.dispatch.SyncHandlerExecutor;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.metadata.MetadataFactory;
import com.jinternals.webhooks.consumer.metrics.WebhookMetrics;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.support.LogCapture;
import com.jinternals.webhooks.consumer.validation.PayloadValidator;
import com.jinternals.webhooks.consumer.verify.VerificationFailureReason;
import com.jinternals.webhooks.consumer.verify.WebhookVerificationException;
import com.jinternals.webhooks.consumer.verify.WebhookVerificationFailure;
import com.jinternals.webhooks.consumer.verify.WebhookVerifier;
import com.jinternals.webhooks.consumer.web.error.EndpointNotConfiguredException;
import com.jinternals.webhooks.consumer.web.error.InvalidPayloadException;
import com.jinternals.webhooks.consumer.web.error.NoWebhookHandlerException;
import com.jinternals.webhooks.consumer.web.error.PayloadValidationException;
import com.jinternals.webhooks.consumer.web.error.UnknownTypeException;
import com.jinternals.webhooks.consumer.web.receive.EventResolver;
import com.jinternals.webhooks.consumer.web.receive.ReceiveObservabilityRecorder;
import com.jinternals.webhooks.consumer.web.receive.TimedHandlerDispatcher;
import com.jinternals.webhooks.consumer.web.receive.WebhookBodyVerifier;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.core.secret.SecretProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class WebhookReceiveControllerTest {

    private WebhookVerifier verifier;
    private SecretProvider<Endpoint> secretProvider;
    private WebhookTypeRegistry typeRegistry;
    private WebhookDispatcher dispatcher;
    private MetadataFactory metadataFactory;
    private PayloadValidator payloadValidator;
    private IdempotencyGuard idempotency;
    private WebhookProperties properties;
    private WebhookMetrics metrics;
    private WebhookReceiveController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(WebhookVerifier.class);
        @SuppressWarnings("unchecked")
        SecretProvider<Endpoint> mockSecretProvider = mock(SecretProvider.class);
        secretProvider = mockSecretProvider;
        typeRegistry = mock(WebhookTypeRegistry.class);
        dispatcher = mock(WebhookDispatcher.class);
        metadataFactory = mock(MetadataFactory.class);
        payloadValidator = mock(PayloadValidator.class);
        idempotency = mock(IdempotencyGuard.class);
        properties = new WebhookProperties();
        metrics = mock(WebhookMetrics.class);

        when(secretProvider.secretsFor(any())).thenReturn(List.of("whsec_x"));
        // Default: every id is new (reserved) so the happy paths proceed; tests override as needed.
        when(idempotency.tryBegin(any())).thenReturn(true);

        controller = controllerWith(metrics);
    }

    /**
     * Build the thin controller over a real {@link WebhookReceivePipeline} wired from the cohesive stage
     * collaborators, so these tests exercise the end-to-end receive flow through the public
     * {@code handle(...)} seam. {@code metrics} is a parameter so a test can supply a fresh mock when it
     * needs isolated verification.
     */
    private WebhookReceiveController controllerWith(WebhookMetrics metrics) {
        WebhookBodyVerifier bodyVerifier = new WebhookBodyVerifier(verifier, secretProvider, metrics);
        EventResolver eventResolver = new EventResolver(typeRegistry, payloadValidator, new ObjectMapper(), properties);
        ReceiveObservabilityRecorder observability = new ReceiveObservabilityRecorder(metrics);
        TimedHandlerDispatcher eventDispatcher =
                new TimedHandlerDispatcher(new SyncHandlerExecutor(), dispatcher, metrics);
        WebhookReceivePipeline pipeline = new WebhookReceivePipeline(
                bodyVerifier,
                idempotency,
                new ObjectMapper(),
                eventResolver,
                metadataFactory,
                observability,
                eventDispatcher);
        return new WebhookReceiveController(pipeline);
    }

    static class ContactCreated {
        public String id;
    }

    private MockHttpServletRequest request(Endpoint endpoint, String id, String ts) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/crm");
        if (endpoint != null) {
            request.setAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE, endpoint);
        }
        if (id != null) {
            request.addHeader("webhook-id", id);
        }
        if (ts != null) {
            request.addHeader("webhook-timestamp", ts);
        }
        return request;
    }

    private Endpoint endpoint(String path, List<String> types) {
        Endpoint e = new Endpoint();
        e.setPath(path);
        e.setSecret("whsec_x");
        e.setTypes(types);
        return e;
    }

    private WebhookMetadata meta() {
        return new WebhookMetadata(
                "contact.created",
                "msg_1",
                Instant.now().minusSeconds(1),
                Instant.now().minusSeconds(2),
                "/webhooks/crm",
                "ip",
                new Object(),
                Map.of(),
                Map.of());
    }

    @Test
    void throwsWhenEndpointNotMatched() {
        MockHttpServletRequest request = request(null, "msg_1", "1700000000");
        assertThatThrownBy(() -> controller.handle(request, "{}")).isInstanceOf(EndpointNotConfiguredException.class);
    }

    @Test
    void verificationFailureRethrows() throws Exception {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        doThrow(new WebhookVerificationFailure(VerificationFailureReason.SIGNATURE, "No matching signature found"))
                .when(verifier)
                .verify(any(), any(), anyString(), any());

        assertThatThrownBy(() -> controller.handle(request, "{}")).isInstanceOf(WebhookVerificationException.class);
        // Verification failed before parsing/dispatch: nothing is dispatched.
        verify(dispatcher, never()).dispatch(any(), any());
        // The failure is counted by reason (SPEC §20.6/A4); "signature" for a no-match.
        verify(metrics).verificationFailed("signature");
        // Verification happens before dedupe reservation: no id was reserved.
        verify(idempotency, never()).tryBegin(any());
    }

    @Test
    void verificationFailureReasonsAreMappedToLowCardinalityTags() throws Exception {
        // Each typed verification failure maps to its stable reason tag (SPEC §20.6/A4). Driven through
        // the real controller so the mapping is exercised end-to-end, not in isolation.
        assertReasonFor(
                new WebhookVerificationFailure(VerificationFailureReason.MISSING_HEADERS, "m"), "missing-headers");
        assertReasonFor(new WebhookVerificationFailure(VerificationFailureReason.TIMESTAMP, "m"), "timestamp");
        assertReasonFor(new WebhookVerificationFailure(VerificationFailureReason.NO_SECRET, "m"), "no-secret");
        assertReasonFor(new WebhookVerificationFailure(VerificationFailureReason.SIGNATURE, "m"), "signature");
        // A plain exception (e.g. from a host's custom TimestampValidator) has no typed reason => "other".
        assertReasonFor(new WebhookVerificationException("something unexpected"), "other");
    }

    private void assertReasonFor(WebhookVerificationException thrown, String expectedReason) throws Exception {
        WebhookMetrics localMetrics = mock(WebhookMetrics.class);
        WebhookReceiveController localController = controllerWith(localMetrics);
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        doThrow(thrown).when(verifier).verify(any(), any(), anyString(), any());

        assertThatThrownBy(() -> localController.handle(request, "{}"))
                .isInstanceOf(WebhookVerificationException.class);
        verify(localMetrics).verificationFailed(expectedReason);
    }

    @Test
    void duplicateWebhookShortCircuitsTo204() throws Exception {
        // Full happy-path collaborators are stubbed, so if dedupe were bypassed the event WOULD
        // dispatch. Asserting dispatch never runs proves the tryBegin()==false short-circuit is real.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());
        when(idempotency.tryBegin("msg_1")).thenReturn(false);

        ResponseEntity<Void> response =
                controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(idempotency).tryBegin("msg_1"); // proves the real webhook-id was extracted
        verify(dispatcher, never()).dispatch(any(), any());
        // A skipped duplicate neither commits nor releases a reservation (it never held one).
        verify(idempotency, never()).markProcessed(any());
        verify(idempotency, never()).release(any());
    }

    @Test
    void newWebhookIdIsNotShortCircuited() throws Exception {
        // tryBegin()==true must NOT short-circuit: the event dispatches. Kills the mutant that forces
        // the dedupe branch to a constant.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());
        when(idempotency.tryBegin("msg_1")).thenReturn(true);

        controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
        verify(dispatcher).dispatch(any(ContactCreated.class), any(WebhookMetadata.class));
    }

    @Test
    void reservationIsCommittedOnlyAfterSuccessfulDispatch() throws Exception {
        // Reserve-then-commit: the id is reserved (tryBegin) before dispatch and committed (markProcessed)
        // only after dispatch returns. Order proves the commit does not happen pre-dispatch.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");

        InOrder order = inOrder(idempotency, dispatcher);
        order.verify(idempotency).tryBegin("msg_1");
        order.verify(dispatcher).dispatch(any(ContactCreated.class), any(WebhookMetadata.class));
        order.verify(idempotency).markProcessed("msg_1");
        verify(idempotency, never()).release(any());
    }

    @Test
    void reservationIsReleasedWhenSyncHandlerFails() throws Exception {
        // Sync handler failure must NOT leave the id marked seen: the reservation is released so the
        // sender's retry of the same id is allowed to run (the core reserve/commit correctness property).
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());
        doThrow(new RuntimeException("handler blew up")).when(dispatcher).dispatch(any(), any());

        assertThatThrownBy(() -> controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler blew up");

        verify(idempotency).tryBegin("msg_1");
        verify(idempotency).release("msg_1");
        verify(idempotency, never()).markProcessed(any());
    }

    @Test
    void reservationIsCommittedWhenTypeIsIgnored() throws Exception {
        // An ack-and-ignore (unmodelled type) still processed the delivery to completion, so the
        // reservation is committed — a retry of the same id stays deduped.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn(null);

        ResponseEntity<Void> response = controller.handle(request, "{\"type\":\"contact.created\",\"data\":{}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(idempotency).markProcessed("msg_1");
        verify(idempotency, never()).release(any());
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void reservationIsReleasedWhenPayloadIsInvalid() throws Exception {
        // A 400 payload path is a failure to process, so the reservation is released (the sender may
        // correct the payload under the same id, though in practice a bad payload won't become valid).
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(
                        () -> controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"unknown\":\"x\"}}"))
                .isInstanceOf(InvalidPayloadException.class);

        verify(idempotency).release("msg_1");
        verify(idempotency, never()).markProcessed(any());
    }

    @Test
    void typeNotAllowListedAcksAndIgnoresByDefault() throws Exception {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.updated"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        // A @WebhookType class IS registered + metadata stubbed, so if the allow-list check were bypassed the
        // event WOULD dispatch. Asserting no dispatch proves the "!accepts" short-circuit is real (kills
        // the mutant that forces accepts() to true).
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        ResponseEntity<Void> response = controller.handle(request, "{\"type\":\"contact.created\",\"data\":{}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void typeNotAllowListedRejectsUnderRejectPolicy() {
        properties.getConsumer().setUnknownTypePolicy(UnknownTypePolicy.REJECT);
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.updated"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");

        assertThatThrownBy(() -> controller.handle(request, "{\"type\":\"contact.created\",\"data\":{}}"))
                .isInstanceOf(UnknownTypeException.class);
    }

    @Test
    void noTypeClassAcksAndIgnores() throws Exception {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn(null);

        ResponseEntity<Void> response = controller.handle(request, "{\"type\":\"contact.created\",\"data\":{}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void malformedBodyIsA400() {
        // A verified-but-unparseable body is a sender error (400), not a 500: it must surface as
        // InvalidPayloadException so the GlobalExceptionHandler maps it to 400 and the sender stops
        // retrying a payload that can never parse.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");

        assertThatThrownBy(() -> controller.handle(request, "this is not json"))
                .isInstanceOf(InvalidPayloadException.class);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void envelopeMissingTypeIsA400() {
        // A signed envelope with no routable type can't be matched against the allow-list/registry; it
        // is a malformed payload (400) regardless of the unknown-type policy.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");

        assertThatThrownBy(() -> controller.handle(request, "{\"data\":{}}"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing a 'type'");
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void envelopeBlankTypeIsA400() {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");

        assertThatThrownBy(() -> controller.handle(request, "{\"type\":\"   \",\"data\":{}}"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("missing a 'type'");
    }

    @Test
    void unknownJsonFieldInDataIsA400() {
        // Strict deserialization rejects unknown fields on the @WebhookType class (SPEC §6a): a sender
        // error surfaced as InvalidPayloadException (400), carrying the type for diagnostics.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(() -> controller.handle(
                        request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\",\"nope\":true}}"))
                .isInstanceOf(InvalidPayloadException.class);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void malformedDataValueIsA400() {
        // A data value that cannot bind to the field type (object where a String is expected) is a
        // Jackson failure wrapped as InvalidPayloadException (400), not an opaque 500.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);

        assertThatThrownBy(() ->
                        controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":{\"nested\":1}}}"))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void payloadValidationFailureThrows() {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        // Validation runs on the deserialized event (the @WebhookType object), after treeToValue.
        doThrow(new PayloadValidationException("contact.created", List.of("id must not be blank")))
                .when(payloadValidator)
                .validate(eq("contact.created"), any());

        assertThatThrownBy(() -> controller.handle(request, "{\"type\":\"contact.created\",\"data\":{}}"))
                .isInstanceOf(PayloadValidationException.class);
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void noHandlerForModelledTypePropagates() throws Exception {
        // A modelled, accepted type whose handler throws NoWebhookHandlerException (no @WebhookHandler)
        // must surface, not be silently 204'd. The sync dispatcher runs inline, so it propagates to the
        // controller for the GlobalExceptionHandler to map to 500.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());
        doThrow(new NoWebhookHandlerException("contact.created", ContactCreated.class.getName()))
                .when(dispatcher)
                .dispatch(any(), any());

        assertThatThrownBy(() -> controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}"))
                .isInstanceOf(NoWebhookHandlerException.class);
        verify(idempotency).release("msg_1");
    }

    @Test
    void happyPathDispatchesAndReturns204() throws Exception {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        ResponseEntity<Void> response =
                controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(dispatcher).dispatch(any(ContactCreated.class), any(WebhookMetadata.class));
    }

    @Test
    void happyPathRecordsReceivedAndLagMetricsAndHandlerDuration() throws Exception {
        // SPEC §20.6/A2+A4: on the verified happy path the controller records the received counter, both
        // lag summaries (meta() has both timestamps), and the handler's execution duration.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");

        verify(metrics).received("contact.created");
        verify(metrics).deliveryLag(any(java.time.Duration.class));
        verify(metrics).eventLag(any(java.time.Duration.class)); // meta() has an eventTimestamp
        // Sync executor runs the handler inline, so handle.duration is recorded before handle() returns.
        // Capture the duration and assert it is a real elapsed time: non-negative and small (well under a
        // second). This kills the mutant that turns (now - start) into (now + start), which would record a
        // ~584-year duration. (Duration.ofNanos overflows to a huge value, so an upper bound is the check.)
        ArgumentCaptor<java.time.Duration> handled = ArgumentCaptor.forClass(java.time.Duration.class);
        verify(metrics).handled(eq("contact.created"), handled.capture());
        assertThat(handled.getValue())
                .isGreaterThanOrEqualTo(java.time.Duration.ZERO)
                .isLessThan(java.time.Duration.ofSeconds(60));
    }

    @Test
    void eventLagMetricIsSkippedWhenTheBodyHadNoTimestamp() throws Exception {
        // When the body carried no timestamp, eventLag() is empty and the event-lag meter is NOT touched,
        // while delivery-lag (always present) still is. Proves the Optional branch (SPEC §20.6/A2).
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        WebhookMetadata noEventTs = new WebhookMetadata(
                "contact.created",
                "msg_1",
                Instant.now().minusSeconds(1),
                null, // eventTimestamp absent
                "/webhooks/crm",
                "ip",
                new Object(),
                Map.of(),
                Map.of());
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(noEventTs);

        controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");

        verify(metrics).deliveryLag(any(java.time.Duration.class));
        verify(metrics, never()).eventLag(any());
    }

    @Test
    void verifiedLogLineCarriesTheLags() throws Exception {
        // SPEC §20.6/A2: the verified log line reports delivery-lag (ms) and event-lag.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        try (LogCapture logs = LogCapture.forClass(ReceiveObservabilityRecorder.class)) {
            controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");

            // meta() has an eventTimestamp ~2s old, so a concrete "<digits>ms" value is rendered — not an
            // empty string (kills the mutant that blanks the eventLag formatting lambda).
            assertThat(logs.lastFormattedMessage())
                    .contains("Webhook verified")
                    .contains("type=contact.created")
                    .containsPattern("deliveryLag=\\d+ms")
                    .containsPattern("eventLag=\\d+ms");
        }
    }

    @Test
    void verifiedLogLineShowsEventLagNaWhenBodyHadNoTimestamp() throws Exception {
        // SPEC §20.6/A2: with no body timestamp the verified line renders eventLag=n/a (not a crash).
        Endpoint endpoint = endpoint("/webhooks/crm", List.of("contact.created"));
        MockHttpServletRequest request = request(endpoint, "msg_1", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        WebhookMetadata noEventTs = new WebhookMetadata(
                "contact.created",
                "msg_1",
                Instant.now().minusSeconds(1),
                null,
                "/webhooks/crm",
                "ip",
                new Object(),
                Map.of(),
                Map.of());
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(noEventTs);

        try (LogCapture logs = LogCapture.forClass(ReceiveObservabilityRecorder.class)) {
            controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
            assertThat(logs.lastFormattedMessage()).contains("eventLag=n/a");
        }
    }

    @Test
    void receivedLineIsLoggedBeforeVerificationRuns() throws Exception {
        // The "Webhook received, verifying" line is emitted before verify() is called, so even a
        // verification-failure delivery produces a received line. The toolkit does not touch the MDC
        // (correlation is the application's concern, SPEC §9b); the stable webhook-id reaches handlers via
        // WebhookMetadata.webhookId().
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest request = request(endpoint, "msg_abc", "1700000000");
        doThrow(new WebhookVerificationException("No matching signature found"))
                .when(verifier)
                .verify(any(), any(), anyString(), any());

        try (LogCapture logs = LogCapture.forClass(WebhookReceivePipeline.class)) {
            assertThatThrownBy(() -> controller.handle(request, "{}")).isInstanceOf(WebhookVerificationException.class);

            // The first line ("Webhook received, verifying") was emitted before verify() ran.
            assertThat(logs.firstFormattedMessage()).contains("Webhook received");
        }
    }

    @Test
    void missingWebhookIdHeaderHandledGracefully() throws Exception {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        // No webhook-id header -> first() returns null, idempotency sees null.
        MockHttpServletRequest request = request(endpoint, null, "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        ResponseEntity<Void> response =
                controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // first() must return null (not "") for an absent header: idempotency sees null.
        verify(idempotency).tryBegin(isNull());
    }

    @Test
    void nullHeaderNamesDoesNotBreakHeaderCollection() throws Exception {
        // Servlet spec allows getHeaderNames() to return null; the defensive guard must handle it.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE)).thenReturn(endpoint);
        when(request.getHeaderNames()).thenReturn(null);
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        ResponseEntity<Void> response =
                controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void headerWithEmptyValueListTreatedAsAbsent() throws Exception {
        // A header name present but with an empty value enumeration exercises the isEmpty() guard
        // in first(): webhook-id resolves to null, so processing continues normally.
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getAttribute(WebhookReceiveController.ENDPOINT_ATTRIBUTE)).thenReturn(endpoint);
        when(request.getHeaderNames()).thenReturn(java.util.Collections.enumeration(List.of("webhook-id")));
        when(request.getHeaders("webhook-id")).thenReturn(java.util.Collections.emptyEnumeration());
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());

        ResponseEntity<Void> response =
                controller.handle(request, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Present-but-empty header value list resolves to null (not ""), same as absent.
        verify(idempotency).tryBegin(isNull());
    }

    @Test
    void rejectsJsonNullLiteralWithInvalidPayload400ReleasesOnErrorAndFailsEagerlyBeforeAsyncSubmit() {
        Endpoint endpoint = endpoint("/webhooks/crm", List.of());
        MockHttpServletRequest nullBodyReq = request(endpoint, "msg_null", "1700000000");

        assertThatThrownBy(() -> controller.handle(nullBodyReq, "null"))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("JSON object");
        verify(idempotency).release("msg_null");

        // Error (e.g. AssertionError) thrown during dispatch MUST also release idempotency reservation!
        MockHttpServletRequest errReq = request(endpoint, "msg_err", "1700000000");
        when(typeRegistry.classOf("contact.created")).thenReturn((Class) ContactCreated.class);
        when(metadataFactory.build(any(), any(), any(), any(), anyString())).thenReturn(meta());
        doThrow(new AssertionError("simulated linkage/assertion error"))
                .when(dispatcher)
                .dispatch(any(), any());
        assertThatThrownBy(() -> controller.handle(errReq, "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}"))
                .isInstanceOf(AssertionError.class);
        verify(idempotency).release("msg_err");

        // Missing @WebhookHandler fails eagerly via requireHandler BEFORE HandlerExecutor.execute runs!
        com.jinternals.webhooks.consumer.dispatch.HandlerExecutor neverExecuted =
                mock(com.jinternals.webhooks.consumer.dispatch.HandlerExecutor.class);
        WebhookDispatcher missingDispatcher = mock(WebhookDispatcher.class);
        doThrow(new NoWebhookHandlerException("contact.created", ContactCreated.class.getName()))
                .when(missingDispatcher)
                .requireHandler(any(), any());
        TimedHandlerDispatcher timed = new TimedHandlerDispatcher(neverExecuted, missingDispatcher, metrics);
        assertThatThrownBy(() -> timed.dispatch("contact.created", new ContactCreated(), meta()))
                .isInstanceOf(NoWebhookHandlerException.class);
        verify(neverExecuted, never()).execute(anyString(), any());
    }
}
