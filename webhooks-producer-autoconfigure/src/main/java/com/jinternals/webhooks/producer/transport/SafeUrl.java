package com.jinternals.webhooks.producer.transport;

import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;

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
 * drops the userinfo, query, and fragment. A URL that cannot be parsed is reduced to its scheme+authority+path
 * prefix (up to the first {@code '?'} or {@code '#'}) so a malformed value with a trailing {@code ?token=…} or
 * {@code #secret} still does not leak. This is a logging aid only; the real, unmodified URL is always used for
 * the actual delivery.
 */
public final class SafeUrl {

    private SafeUrl() {}

    /** The redaction placeholder substituted for a {@code null} URL. */
    static final String NULL_URL = "<null>";

    /**
     * Return a log-safe rendering of {@code url}: scheme, host, port and path only — userinfo, query, and
     * fragment removed. Never returns {@code null}.
     */
    public static String sanitize(@Nullable String url) {
        if (url == null) {
            return NULL_URL;
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                // No parseable authority (e.g. an opaque or relative value): best-effort strip of both the
                // userinfo and anything from the first "?" or "#" so neither "user:token@" nor "?token=…" leaks.
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
            // the userinfo, query, and fragment so neither embedded credentials nor a "?token=…" leak.
            return bestEffortStrip(url);
        }
    }

    /**
     * Best-effort redaction for a value {@link URI} could not give us a host for (opaque, relative, or
     * outright unparseable): drop the userinfo ({@code //user:token@} → {@code //}) and anything from the
     * first {@code '?'} or {@code '#'}. This is textual, not a parse, precisely because the input may be
     * malformed; it keeps the useful prefix while ensuring the secret-bearing components cannot survive.
     */
    private static String bestEffortStrip(String url) {
        return stripUserInfo(stripQueryAndFragment(url));
    }

    /** Matches a query ({@code ?…}) or fragment ({@code #…}) suffix to the end of the string (including newlines). */
    private static final java.util.regex.Pattern QUERY_OR_FRAGMENT =
            java.util.regex.Pattern.compile("[?#].*", java.util.regex.Pattern.DOTALL);

    /** Matches a {@code #fragment} suffix to the end of the string (including newlines). */
    private static final java.util.regex.Pattern FRAGMENT =
            java.util.regex.Pattern.compile("#.*", java.util.regex.Pattern.DOTALL);

    private static String stripQueryAndFragment(String url) {
        return QUERY_OR_FRAGMENT.matcher(url).replaceFirst("");
    }

    /**
     * Pattern matching a userinfo ({@code user:password@}) segment at the start of a double-slash authority.
     * Group 1 captures the scheme-and-authority-start prefix up to and including the double slash (so it works
     * for both an absolute {@code https:} URL and a scheme-relative one); the userinfo that follows is the
     * greedy run of authority characters up to the <em>last</em> {@code '@'} before a path/query/fragment/whitespace
     * boundary — so a password that itself contains {@code '@'} is stripped in full while an {@code '@'} appearing
     * later in the path is never mistaken for a userinfo delimiter.
     */
    private static final java.util.regex.Pattern USERINFO = java.util.regex.Pattern.compile("(^[^/\\s]*//)[^/?#\\s]*@");

    /**
     * Matches any {@code scheme://userinfo@} token embedded inside an HTTP client exception message (for
     * example when Spring's {@code RestClient} strips {@code ?query}/{@code #fragment} or re-encodes
     * {@code %}-escapes before formatting {@code ResourceAccessException}, so the echoed URL no longer matches
     * {@code url} byte-for-byte). Excludes {@code /}, {@code ?}, {@code #}, whitespace, and quotes so an
     * {@code '@'} inside a path, query parameter, or fragment is never mistaken for {@code userinfo}.
     */
    private static final java.util.regex.Pattern EMBEDDED_USERINFO =
            java.util.regex.Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#\\s\"']+@");

    private static String stripUserInfo(String url) {
        return USERINFO.matcher(url).replaceFirst("$1");
    }

    /**
     * Redact a secret-bearing {@code url} wherever it appears inside a human-readable {@code message} —
     * replacing every literal occurrence of the raw URL (and its fragment-stripped form, for clients that drop
     * {@code #fragment} before formatting errors) with its {@link #sanitize(String) sanitized} form, and
     * scrubbing any remaining {@code scheme://userinfo@} credentials that the HTTP client echoed after
     * stripping query components or re-encoding escapes.
     *
     * @param message the exception/log message (may be {@code null})
     * @param url     the raw destination URL that may be embedded in {@code message} (may be {@code null})
     * @return the message with {@code url} and embedded {@code userinfo} redacted; {@code null} in,
     *     {@code null} out
     */
    @Nullable
    public static String redactMessage(@Nullable String message, @Nullable String url) {
        if (message == null || url == null) {
            return message;
        }
        String sanitized = sanitize(url);
        String withoutFragment = FRAGMENT.matcher(url).replaceFirst("");
        String replaced = message.replace(url, sanitized).replace(withoutFragment, sanitized);
        return EMBEDDED_USERINFO.matcher(replaced).replaceAll("$1");
    }
}
