package com.jinternals.webhooks.producer.transport;

import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Default synchronous {@link WebhookTransport}: POSTs the signed envelope via Spring's
 * {@code RestClient} and surfaces the outcome to the caller (see SPEC.md §19). A non-2xx response or a
 * transport error becomes a {@link WebhookDeliveryException}, carrying the HTTP status when there was a
 * response so a retry policy can classify it (§19b). This base transport performs no retry; it validates
 * the destination URL ({@link DestinationUrlValidator}, §19d) before each send so a disallowed URL fails
 * fast (and is never retried, since validation runs before the request is attempted).
 *
 * <p>When the failing response carries a {@code Retry-After} header it is parsed (both the
 * delta-seconds and the HTTP-date forms) and attached to the exception, so a retry policy can honor the
 * server's requested delay (SPEC §20.6/A1).
 */
public class RestClientWebhookTransport implements WebhookTransport {

    private final RestClient restClient;
    private final DestinationUrlValidator urlValidator;
    private final Clock clock;

    public RestClientWebhookTransport(RestClient restClient, DestinationUrlValidator urlValidator) {
        this(restClient, urlValidator, Clock.systemUTC());
    }

    public RestClientWebhookTransport(RestClient restClient, DestinationUrlValidator urlValidator, Clock clock) {
        this.restClient = restClient;
        this.urlValidator = urlValidator;
        this.clock = clock;
    }

    @Override
    public void send(String url, Map<String, List<String>> headers, String body) {
        urlValidator.validate(url);
        try {
            restClient
                    .post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::addAll))
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        throw new WebhookDeliveryException(
                                "Webhook delivery to " + SafeUrl.sanitize(url) + " failed with status " + status,
                                status,
                                parseRetryAfter(response.getHeaders(), clock));
                    })
                    .toBodilessEntity();
        } catch (WebhookDeliveryException e) {
            throw e;
        } catch (RestClientException e) {
            // The client echoes the full raw request URL into its message, so redact the URL out of the cause
            // message before it becomes part of our (logged) exception, or userinfo/query secrets would leak.
            throw new WebhookDeliveryException(
                    "Webhook delivery to " + SafeUrl.sanitize(url) + " failed: "
                            + SafeUrl.redactMessage(e.getMessage(), url),
                    e);
        } catch (IllegalArgumentException e) {
            // A malformed destination URL is rejected by RestClient's URI builder with an
            // IllegalArgumentException; wrap it so every delivery failure surfaces as the single
            // WebhookDeliveryException contract (a transport-level failure, so no HTTP status). Redact the raw
            // URL the builder may have placed in its message.
            throw new WebhookDeliveryException(
                    "Webhook delivery to " + SafeUrl.sanitize(url) + " failed: "
                            + SafeUrl.redactMessage(e.getMessage(), url),
                    e);
        }
    }

    /**
     * Parse a {@code Retry-After} header (RFC 7231): either delta-seconds (e.g. {@code "120"}) or an
     * HTTP-date (e.g. {@code "Wed, 21 Oct 2015 07:28:00 GMT"}). Returns {@code null} when the header is
     * absent or unparseable, and clamps a past HTTP-date to {@link Duration#ZERO} rather than negative.
     */
    @Nullable
    static Duration parseRetryAfter(HttpHeaders responseHeaders) {
        return parseRetryAfter(responseHeaders, Clock.systemUTC());
    }

    @Nullable
    static Duration parseRetryAfter(HttpHeaders responseHeaders, Clock clock) {
        String value = responseHeaders.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        // A blank value needs no guard of its own: the trimmed empty string fails both Long.parseLong
        // and the HTTP-date parse below, so it falls through to null — the same result a guard would give.
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            // Clamp a negative delta-seconds up to zero (never a negative sleep). Using Math.max rather
            // than a boundary conditional keeps the intent explicit and leaves no equivalent branch.
            return Duration.ofSeconds(Math.max(0L, seconds));
        } catch (NumberFormatException notDeltaSeconds) {
            return parseHttpDate(trimmed, clock);
        }
    }

    @Nullable
    private static Duration parseHttpDate(String value, Clock clock) {
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration delay = Duration.between(clock.instant(), when.toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException notAnHttpDate) {
            return null;
        }
    }
}
