package com.jinternals.webhooks.consumer.metadata;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.model.WebhookHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the immutable {@link WebhookMetadata} handed to handlers (see SPEC.md §9).
 *
 * <p>Redacts {@code webhook-signature} from the headers map so the object is safe to log, and runs any
 * {@link MetadataContributor} beans. Correlation/trace ids are the application's concern (SPEC.md §9b)
 * and are not read or carried here.
 *
 * <p><strong>Redaction is shallow by design.</strong> Only {@code webhook-signature} is masked; the
 * metadata carries the remaining headers verbatim so handlers can read them. If a deployment forwards
 * additional secrets in custom headers (an unusual choice), it is the deployment's responsibility to
 * strip them — e.g. with a {@link MetadataContributor} — before anything logs the metadata.
 */
public class MetadataFactory {

    private static final String REDACTED = "***REDACTED***";

    /**
     * Header names (lower-cased) redacted on metadata construction: the Standard Webhooks signature plus
     * standard transport-credential headers (e.g. OAuth2 Bearer tokens recommended for transport auth).
     */
    private static final Set<String> REDACTED_HEADERS =
            Set.of(WebhookHeaders.SIGNATURE, "authorization", "proxy-authorization", "cookie");

    private final List<MetadataContributor> contributors;

    public MetadataFactory(List<MetadataContributor> contributors) {
        this.contributors = contributors;
    }

    public WebhookMetadata build(
            HttpServletRequest request,
            Envelope envelope,
            Object event,
            Map<String, List<String>> rawHeaders,
            String endpointPath) {

        Map<String, String> headers = flattenAndRedact(rawHeaders);
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (MetadataContributor contributor : contributors) {
            contributor.contribute(request, headers, attributes);
        }

        return new WebhookMetadata(
                envelope.getType(),
                first(headers, WebhookHeaders.ID),
                parseAttemptTimestamp(first(headers, WebhookHeaders.TIMESTAMP)),
                parseEventTimestamp(envelope.getTimestamp()),
                endpointPath,
                clientIp(request),
                event,
                headers,
                attributes);
    }

    /**
     * Flatten the already-collected (lower-cased, {@code Locale.ROOT}) request headers to first-value
     * strings, redacting {@code webhook-signature} so the metadata object is safe to log. Reuses the
     * headers the pipeline already collected rather than re-enumerating the servlet request.
     */
    private Map<String, String> flattenAndRedact(Map<String, List<String>> rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            List<String> values = entry.getValue();
            String value = REDACTED_HEADERS.contains(name)
                    ? REDACTED
                    : (values == null || values.isEmpty()) ? null : values.get(0);
            headers.put(name, value);
        }
        return headers;
    }

    private static String first(Map<String, String> headers, String name) {
        return headers.get(name);
    }

    /** webhook-timestamp is unix seconds (see STANDARD_WEBHOOKS.md §3a). */
    private static Instant parseAttemptTimestamp(String value) {
        // A blank value is not special-cased: Long.parseLong("") throws and is caught below, giving
        // the same null result, so an explicit isBlank() check would be a redundant (equivalent) branch.
        if (value == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.trim()));
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /** Body timestamp is ISO 8601 and nullable. */
    private static Instant parseEventTimestamp(String value) {
        // As above: blank falls through to Instant.parse(""), which throws and is caught, so there
        // is no observable difference from an explicit blank check.
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Best-effort client IP for observability only. Prefers the first hop in {@code x-forwarded-for},
     * falling back to the socket's remote address. {@code x-forwarded-for} is client-spoofable unless a
     * trusted proxy sets it, so this value is <strong>not</strong> a security control — never gate access
     * or trust decisions on it; verification (SPEC §8) is the only authentication.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("x-forwarded-for");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
