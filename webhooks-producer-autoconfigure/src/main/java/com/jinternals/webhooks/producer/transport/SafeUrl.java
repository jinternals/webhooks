package com.jinternals.webhooks.producer.transport;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.lang.Nullable;

/**
 * Renders a destination URL in a form that is safe to place in logs and exception messages (see SPEC.md
 * §19c). A webhook URL can legitimately carry secrets in two places that must never reach a log sink:
 *
 * <ul>
 *   <li><strong>userinfo</strong> — {@code https://user:token@host/…} embeds credentials in the authority;
 *   <li><strong>query string</strong> — {@code …?access_token=…} is a common (if discouraged) way to pass a
 *       shared secret to a receiver.
 * </ul>
 *
 * <p>{@link #sanitize(String)} keeps the parts that make a log entry useful (scheme, host, port, path) and
 * drops the userinfo and query. A URL that cannot be parsed is reduced to its scheme+authority prefix (up to
 * the first {@code '?'}) so a malformed value with a trailing {@code ?token=…} still does not leak. This is a
 * logging aid only; the real, unmodified URL is always used for the actual delivery.
 */
public final class SafeUrl {

    private SafeUrl() {}

    /** The redaction placeholder substituted for a {@code null} URL. */
    static final String NULL_URL = "<null>";

    /**
     * Return a log-safe rendering of {@code url}: scheme, host, port and path only — userinfo and query
     * removed. Never returns {@code null}.
     */
    public static String sanitize(@Nullable String url) {
        if (url == null) {
            return NULL_URL;
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                // No parseable authority (e.g. an opaque or relative value): best-effort strip of both the
                // userinfo and anything from the first "?" so neither "user:token@" nor "?token=…" can leak.
                return bestEffortStrip(url);
            }
            StringBuilder safe = new StringBuilder();
            if (uri.getScheme() != null) {
                safe.append(uri.getScheme()).append("://");
            }
            safe.append(uri.getHost());
            if (uri.getPort() != -1) {
                safe.append(':').append(uri.getPort());
            }
            // getRawPath() is never null for a host-bearing URI (it is "" at worst), so no null guard is
            // needed here — appending it unconditionally keeps the path when present and is a no-op otherwise.
            safe.append(uri.getRawPath());
            return safe.toString();
        } catch (URISyntaxException e) {
            // Unparseable URL (e.g. an illegal character): we cannot rely on URI parsing, so best-effort strip
            // the userinfo and query so neither embedded credentials nor a "?token=…" leak.
            return bestEffortStrip(url);
        }
    }

    /**
     * Best-effort redaction for a value {@link URI} could not give us a host for (opaque, relative, or
     * outright unparseable): drop the userinfo ({@code //user:token@} → {@code //}) and anything from the
     * first {@code '?'}. This is textual, not a parse, precisely because the input may be malformed; it keeps
     * the useful prefix while ensuring the two secret-bearing components cannot survive.
     */
    private static String bestEffortStrip(String url) {
        return stripUserInfo(stripQuery(url));
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    /**
     * Pattern matching a userinfo ({@code user:password@}) segment at the start of a double-slash authority.
     * Group 1 captures the scheme-and-authority-start prefix up to and including the double slash (so it works
     * for both an absolute {@code https:} URL and a scheme-relative one); the userinfo that follows is the run
     * of characters up to the first at-sign that does not cross a path slash — so an at-sign appearing later in
     * the path is never mistaken for a userinfo delimiter. Replacing the whole match with group 1 drops only
     * the userinfo; a value without a double-slash authority (e.g. {@code mailto:…}, {@code /path}) does not
     * match and is returned unchanged.
     */
    private static final java.util.regex.Pattern USERINFO = java.util.regex.Pattern.compile("(^[^/]*//)[^/@]*@");

    /**
     * Remove a {@code user:password@} userinfo segment from a {@code scheme://authority…} value, leaving a
     * {@code '@'} that appears later in the path intact. Values without a {@code "//"} authority are returned
     * unchanged. A regex is used deliberately (rather than index arithmetic) so there are no off-by-one
     * boundary conditions to get wrong — or to leave as surviving mutants.
     */
    private static String stripUserInfo(String url) {
        return USERINFO.matcher(url).replaceFirst("$1");
    }

    /**
     * Redact a secret-bearing {@code url} wherever it appears inside a human-readable {@code message} —
     * replacing every literal occurrence of the raw URL with its {@link #sanitize(String) sanitized} form.
     *
     * <p>This exists because the underlying HTTP client echoes the full request URL into its exception
     * messages (e.g. {@code I/O error on POST request for "https://user:token@host/…": …}). Those messages
     * are chained into our {@code WebhookDeliveryException} and then logged, so a message that embeds the raw
     * URL would leak userinfo/query secrets to a log sink even though every URL we format ourselves is already
     * sanitized. Redacting the exact raw URL string covers that echo without mangling the rest of the
     * diagnostic.
     *
     * @param message the exception/log message (may be {@code null})
     * @param url     the raw destination URL that may be embedded in {@code message} (may be {@code null})
     * @return the message with every occurrence of {@code url} replaced by its sanitized form; {@code null} in,
     *     {@code null} out
     */
    @Nullable
    public static String redactMessage(@Nullable String message, @Nullable String url) {
        if (message == null || url == null) {
            return message;
        }
        // An empty url needs no explicit guard: String.replace("", sanitize("")) is replace("", "") — a no-op
        // that returns the message unchanged — so a dedicated isEmpty() branch would be an equivalent mutant.
        return message.replace(url, sanitize(url));
    }
}
