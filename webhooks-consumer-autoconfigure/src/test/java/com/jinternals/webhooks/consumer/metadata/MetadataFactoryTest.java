package com.jinternals.webhooks.consumer.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.model.Envelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MetadataFactoryTest {

    private Envelope envelope(String type, String timestamp) {
        Envelope e = new Envelope();
        e.setType(type);
        e.setTimestamp(timestamp);
        return e;
    }

    @Test
    void buildsMetadataRedactsSignatureAndParsesTimestamps() {
        MetadataFactory factory = new MetadataFactory(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");

        // Headers are supplied as the already-collected (lower-cased) map, exactly as the pipeline hands
        // them in — the factory flattens + redacts THIS map rather than re-enumerating the request.
        Map<String, List<String>> rawHeaders = Map.of(
                "webhook-id", List.of("msg_1"),
                "webhook-timestamp", List.of("1700000000"),
                "webhook-signature", List.of("v1,secret-sig"));

        Object event = new Object();
        WebhookMetadata meta = factory.build(
                request,
                envelope("contact.created", "2022-11-03T20:26:10.344522Z"),
                event,
                rawHeaders,
                "/webhooks/crm");

        assertThat(meta.type()).isEqualTo("contact.created");
        assertThat(meta.webhookId()).isEqualTo("msg_1");
        assertThat(meta.attemptTimestamp()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(meta.eventTimestamp()).isEqualTo(Instant.parse("2022-11-03T20:26:10.344522Z"));
        assertThat(meta.endpointPath()).isEqualTo("/webhooks/crm");
        assertThat(meta.sourceIp()).isEqualTo("10.1.2.3");
        assertThat(meta.event()).isSameAs(event);
        assertThat(meta.headers().get("webhook-signature")).isEqualTo("***REDACTED***");
        assertThat(meta.headers().get("webhook-id")).isEqualTo("msg_1");
        assertThat(meta.header("WEBHOOK-ID")).contains("msg_1");
        assertThat(meta.header("absent")).isEmpty();
    }

    @Test
    void headerWithEmptyValueListFlattensToNull() {
        // A header name present with an empty value list flattens to a null value (not a crash),
        // exercising the isEmpty() guard in flattenAndRedact.
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, List<String>> rawHeaders = new java.util.LinkedHashMap<>();
        rawHeaders.put("x-empty", List.of());

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), rawHeaders, "/p");
        assertThat(meta.headers()).containsKey("x-empty");
        assertThat(meta.headers().get("x-empty")).isNull();
    }

    @Test
    void headerWithNullValueListFlattensToNull() {
        // The guard also tolerates a null value list (not just an empty one): it flattens to null,
        // never NPEs on values.get(0). Pins the `values == null` half of the guard.
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, List<String>> rawHeaders = new java.util.LinkedHashMap<>();
        rawHeaders.put("x-null", null);

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), rawHeaders, "/p");
        assertThat(meta.headers()).containsKey("x-null");
        assertThat(meta.headers().get("x-null")).isNull();
    }

    @Test
    void nonNumericAttemptTimestampResolvesToNull() {
        // webhook-timestamp is unix seconds; a non-numeric value is tolerated (NumberFormatException is
        // swallowed) and the attempt timestamp resolves to null rather than failing the delivery.
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, List<String>> rawHeaders = Map.of("webhook-timestamp", List.of("not-a-number"));

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), rawHeaders, "/p");
        assertThat(meta.attemptTimestamp()).isNull();
    }

    @Test
    void nonEmptyHeaderFlattensToItsFirstValue() {
        // A multi-valued, non-signature header flattens to its FIRST value (not null, not the last).
        // This pins the "else" branch of the empty/null guard — the mutant that forces the guard true
        // (always null) is killed by the concrete first-value assertion.
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, List<String>> rawHeaders = new java.util.LinkedHashMap<>();
        rawHeaders.put("x-multi", List.of("first", "second"));

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), rawHeaders, "/p");
        assertThat(meta.headers().get("x-multi")).isEqualTo("first");
    }

    @Test
    void prefersForwardedForHeaderForSourceIp() {
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-forwarded-for", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("10.9.9.9");

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), Map.of(), "/p");
        assertThat(meta.sourceIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void blankForwardedForFallsBackToRemoteAddr() {
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-forwarded-for", "   ");
        request.setRemoteAddr("10.9.9.9");

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), Map.of(), "/p");
        assertThat(meta.sourceIp()).isEqualTo("10.9.9.9");
    }

    @Test
    void nullTimestampsBecomeNullWithoutError() {
        // No webhook-timestamp header => parseAttemptTimestamp(null); null envelope timestamp =>
        // parseEventTimestamp(null). The null guard must return null, not fall through to
        // null.isBlank() (which the RemoveConditional mutant would do, throwing NPE).
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest(); // no timestamp header

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), Map.of(), "/p");
        assertThat(meta.attemptTimestamp()).isNull();
        assertThat(meta.eventTimestamp()).isNull();
    }

    @Test
    void invalidTimestampsBecomeNull() {
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("webhook-timestamp", "not-a-number");

        WebhookMetadata meta = factory.build(request, envelope("t", "not-a-date"), new Object(), Map.of(), "/p");
        assertThat(meta.attemptTimestamp()).isNull();
        assertThat(meta.eventTimestamp()).isNull();
    }

    @Test
    void blankTimestampsBecomeNull() {
        MetadataFactory factory = new MetadataFactory(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("webhook-timestamp", "   ");

        WebhookMetadata meta = factory.build(request, envelope("t", "  "), new Object(), Map.of(), "/p");
        assertThat(meta.attemptTimestamp()).isNull();
        assertThat(meta.eventTimestamp()).isNull();
    }

    @Test
    void runsContributors() {
        MetadataContributor contributor = (request, headers, attributes) -> attributes.put("x", "y");
        MetadataFactory factory = new MetadataFactory(List.of(contributor));
        MockHttpServletRequest request = new MockHttpServletRequest();

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), Map.of(), "/p");
        assertThat(meta.<String>attribute("x")).contains("y");
        assertThat(meta.attribute("missing")).isEmpty();
    }

    @Test
    void emptyRawHeadersProduceEmptyHeaders() {
        // With no collected headers the metadata carries an empty header map (and still resolves the IP
        // from the request). The factory never re-enumerates the request for headers.
        MetadataFactory factory = new MetadataFactory(List.of());
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        WebhookMetadata meta = factory.build(request, envelope("t", null), new Object(), Map.of(), "/p");
        assertThat(meta.headers()).isEmpty();
        assertThat(meta.sourceIp()).isEqualTo("10.0.0.5");
    }
}
