package com.jinternals.webhooks.consumer.web.error;

/**
 * Raised when a request reaches the generic receive controller but no configured {@code Endpoint}
 * was matched for its path (see SPEC.md §14a). This should not happen in normal operation — every
 * registered path has an endpoint — so it signals a routing/configuration problem rather than a
 * sender error. Maps to {@code 500} (not {@code 4xx}) and is logged; a null endpoint must never
 * cause an unguarded {@code NullPointerException}.
 */
public class EndpointNotConfiguredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EndpointNotConfiguredException(String path) {
        super("No webhook endpoint configured for path: " + path);
    }
}
