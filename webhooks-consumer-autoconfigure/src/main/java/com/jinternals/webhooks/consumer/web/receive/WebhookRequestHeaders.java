package com.jinternals.webhooks.consumer.web.receive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
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
            String lower = name.toLowerCase(Locale.ROOT);
            List<String> incoming = Collections.list(request.getHeaders(name));
            headers.merge(lower, incoming, (existing, extra) -> {
                List<String> merged = new ArrayList<>(existing);
                merged.addAll(extra);
                return merged;
            });
        }
        return headers;
    }

    /**
     * The first value of a header (matched case-insensitively with {@link Locale#ROOT}), or {@code null} when
     * absent or empty — works uniformly for lower-cased maps built by {@link #collect(HttpServletRequest)} and
     * mixed-case maps supplied directly by callers.
     */
    public static String first(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }
}
