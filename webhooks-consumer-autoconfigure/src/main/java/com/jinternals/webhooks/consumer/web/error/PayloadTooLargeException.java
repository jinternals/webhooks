package com.jinternals.webhooks.consumer.web.error;

import java.io.Serial;

/**
 * Raised when an inbound webhook body exceeds {@code webhooks.consumer.max-body-size} (see SPEC.md
 * §14a). Thrown while reading the raw body, <em>before</em> verification, so an oversized or slow
 * POST cannot exhaust memory or threads. Maps to {@code 413 Payload Too Large}.
 */
public class PayloadTooLargeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Webhook body exceeds the configured maximum of " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}
