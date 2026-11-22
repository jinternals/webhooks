package com.jinternals.webhooks.producer.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jinternals.webhooks.producer.security.AllowAllDestinationUrlValidator;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientWebhookTransportTest {

    private static final String URL = "https://crm.example.com/webhooks";
    private static final Map<String, List<String>> HEADERS = Map.of(
            "webhook-id", List.of("msg_1"),
            "webhook-signature", List.of("v1,sig"));

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestClientWebhookTransport transport;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        transport = new RestClientWebhookTransport(builder.build(), new AllowAllDestinationUrlValidator());
    }

    @Test
    void postsSignedBodyWithHeadersOn2xx() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"a\":1}"))
                .andExpect(header("webhook-id", "msg_1"))
                .andExpect(header("webhook-signature", "v1,sig"))
                .andRespond(withSuccess());

        transport.send(URL, HEADERS, "{\"a\":1}");

        server.verify();
    }

    @Test
    void throwsDeliveryExceptionOnServerError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed with status 500")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .extracting(e -> e.getStatusCode().orElseThrow())
                .isEqualTo(500);
    }

    @Test
    void throwsDeliveryExceptionAtStatusBoundary400() {
        // 400 is a non-2xx status and must fail. Together with the 2xx-success and 3xx-failure tests this
        // pins the "only 2xx succeeds" classification.
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed with status 400");
    }

    @Test
    void treatsA3xxRedirectAsAFailedDelivery() {
        // Standard Webhooks: only 2xx is success; a 3xx is a FAILURE ("don't follow redirects — update the
        // webhook URL instead"). A redirect must surface as a WebhookDeliveryException carrying the 3xx
        // status, never silently counted as delivered. This also pins the classification to "not 2xx"
        // rather than ">= 400" (which would wrongly let a 302 through).
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND)); // 302

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed with status 302")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .extracting(e -> e.getStatusCode().orElseThrow())
                .isEqualTo(302);
    }

    @Test
    void treatsA308PermanentRedirectAsAFailedDelivery() {
        // The upper end of the 3xx range must also fail, pinning the "< 200 || >= 300" boundary at 308.
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PERMANENT_REDIRECT)); // 308

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed with status 308");
    }

    @Test
    void treatsA2xxAtTheUpperBoundaryAsSuccess() {
        // 299 is still 2xx and must succeed, pinning the upper success boundary so a mutant narrowing the
        // success range (e.g. only 200) is killed.
        server.expect(requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(299)));

        transport.send(URL, HEADERS, "{\"a\":1}");

        server.verify();
    }

    @Test
    void rejectsADisallowedUrlBeforeSendingAnyRequest() {
        // The base transport is safe when used standalone (unwrapped): its own validate() call must block
        // a disallowed destination before any HTTP request is attempted. The MockRestServiceServer below
        // expects NO request, so server.verify() would fail if validate() were skipped and the POST ran.
        DestinationUrlValidator rejecting = url -> {
            throw new WebhookDeliveryException("Destination host not allowed: " + url);
        };
        RestClientWebhookTransport guarded = new RestClientWebhookTransport(builder.build(), rejecting);

        assertThatThrownBy(() -> guarded.send(URL, HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("not allowed");

        server.verify(); // no request was made
    }

    @Test
    void attachesRetryAfterDeltaSecondsFromAFailingResponse() {
        // A 503 with Retry-After: 120 must surface the parsed delay on the exception so the retry policy
        // can honor it (SPEC §20.6/A1).
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(retryAfter("120")));

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .satisfies(e -> assertThat(e.getRetryAfter()).contains(Duration.ofSeconds(120)));
    }

    @Test
    void attachesRetryAfterHttpDateFromAFailingResponse() {
        // Retry-After may also be an HTTP-date; a date ~10 minutes out must parse to a positive delay.
        String httpDate = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusMinutes(10)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter(httpDate)));

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .satisfies(e -> assertThat(e.getRetryAfter())
                        .isPresent()
                        .get()
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.DURATION)
                        .isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10)));
    }

    @Test
    void failingResponseWithoutRetryAfterCarriesNone() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> transport.send(URL, HEADERS, "{\"a\":1}"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .satisfies(e -> assertThat(e.getRetryAfter()).isEmpty());
    }

    @Test
    void parseRetryAfterHandlesAllForms() {
        // Direct coverage of the static parser's branches (some are unreachable via the mock server):
        // absent, blank, delta-seconds, negative seconds -> ZERO, HTTP-date, past HTTP-date -> ZERO, junk.
        assertThat(RestClientWebhookTransport.parseRetryAfter(new HttpHeaders()))
                .isNull();

        // A blank value trims to "" and fails both parse forms, so it falls through to null.
        HttpHeaders blank = new HttpHeaders();
        blank.set(HttpHeaders.RETRY_AFTER, "   ");
        assertThat(RestClientWebhookTransport.parseRetryAfter(blank)).isNull();

        assertThat(RestClientWebhookTransport.parseRetryAfter(retryAfter("45"))).isEqualTo(Duration.ofSeconds(45));

        // Negative delta-seconds is clamped to zero (never a negative sleep).
        assertThat(RestClientWebhookTransport.parseRetryAfter(retryAfter("-5"))).isEqualTo(Duration.ZERO);

        String future = ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusSeconds(300)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(RestClientWebhookTransport.parseRetryAfter(retryAfter(future)))
                .isBetween(Duration.ofSeconds(290), Duration.ofSeconds(300));

        // An HTTP-date in the past clamps to ZERO rather than going negative.
        String past =
                ZonedDateTime.now(java.time.ZoneOffset.UTC).minusHours(1).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(RestClientWebhookTransport.parseRetryAfter(retryAfter(past))).isEqualTo(Duration.ZERO);

        // Neither a number nor an HTTP-date -> null (ignored).
        assertThat(RestClientWebhookTransport.parseRetryAfter(retryAfter("soon-ish")))
                .isNull();
    }

    private static HttpHeaders retryAfter(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, value);
        return headers;
    }

    @Test
    void wrapsAMalformedDestinationUrlAsADeliveryException() {
        // A malformed URL is rejected by RestClient's URI builder with an IllegalArgumentException (not a
        // RestClientException). It must be wrapped as a transport-level WebhookDeliveryException (no HTTP
        // status) so every delivery failure surfaces through the single exception contract, never as a raw
        // IllegalArgumentException leaking to the caller. No HTTP request is attempted.
        assertThatThrownBy(() -> transport.send("ht tp://not a url", HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed:")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .satisfies(e -> assertThat(e.getStatusCode()).isEmpty());

        server.verify(); // no request was made
    }

    @Test
    void throwsDeliveryExceptionOnTransportError() {
        // No server set up on this builder -> connection/transport error surfaces as RestClientException.
        RestClient bareClient = RestClient.builder().build();
        RestClientWebhookTransport bareTransport =
                new RestClientWebhookTransport(bareClient, new AllowAllDestinationUrlValidator());

        assertThatThrownBy(() -> bareTransport.send("http://localhost:1/webhooks", HEADERS, "{\"a\":1}"))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("failed:")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(WebhookDeliveryException.class))
                .satisfies(e -> assertThat(e.getStatusCode()).isEmpty()); // transport error carries no status
    }
}
