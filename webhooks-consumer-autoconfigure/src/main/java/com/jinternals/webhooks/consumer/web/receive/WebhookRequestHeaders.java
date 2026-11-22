package com.jinternals.webhooks.consumer.web.receive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for reading inbound webhook request headers consistently (see SPEC.md §9b, §10). HTTP
 * header names are case-insensitive, so every lookup goes through a single lower-cased representation
 * built with {@link Locale#ROOT} — locale-independent, so a device set to a locale with non-ASCII
 * casing rules (e.g. Turkish) can never mis-lower a header name like {@code webhook-id}.
 *
 * <p>Centralising this removes the duplicated, subtly-divergent header extraction that previously lived
 * in the controller, the pipeline, and the metadata factory.
 */
public final class WebhookRequestHeaders {

    private WebhookRequestHeaders() {}

    /**
     * Collect all request headers into a map keyed by lower-cased ({@link Locale#ROOT}) name, preserving
     * insertion order and every value for a repeated header.
     */
    public static Map<String, List<String>> collect(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    /**
     * The first value of a header previously collected by {@link #collect(HttpServletRequest)}, or
     * {@code null} when absent or empty. The lookup name is lower-cased with {@link Locale#ROOT} so
     * callers can pass canonical header constants regardless of case.
     */
    public static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
