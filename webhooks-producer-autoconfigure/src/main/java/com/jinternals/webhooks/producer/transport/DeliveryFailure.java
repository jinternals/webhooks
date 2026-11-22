package com.jinternals.webhooks.producer.transport;

import java.util.List;
import java.util.Map;

/**
 * Immutable description of an outbound webhook delivery that was <strong>given up</strong> — the
 * dead-letter signal handed to a {@link WebhookDeliveryListener} (see SPEC.md §20). It is raised once,
 * at the moment the toolkit stops trying: either a fatal {@code 4xx} (never retried) or a retryable
 * failure whose {@code max-attempts} are exhausted.
 *
 * <p>It carries everything needed to persist the failed delivery for later replay — the signed request
 * exactly as it would have been sent, how many attempts were made, and the last failure — so a host can
 * build a durable dead-letter store on top of the otherwise no-storage toolkit.
 *
 * @param url       the destination URL the delivery was targeting
 * @param headers   the request headers, including the Standard Webhooks {@code webhook-*} headers, as
 *                  name -&gt; list of values (the signed, send-ready form)
 * @param body      the raw JSON body exactly as signed
 * @param attempts  how many delivery attempts were made (always {@code >= 1}; {@code 1} for a fatal
 *                  failure that was never retried)
 * @param reason    why the delivery was abandoned — {@link Reason#EXHAUSTED} (retryable, but out of
 *                  attempts) or {@link Reason#FATAL} (a non-retryable {@code 4xx})
 * @param cause     the last {@link WebhookDeliveryException} — carries the final HTTP status, if any
 */
public record DeliveryFailure(
        String url,
        Map<String, List<String>> headers,
        String body,
        int attempts,
        Reason reason,
        WebhookDeliveryException cause) {

    /** Why a delivery was given up. */
    public enum Reason {
        /** The failure was retryable (5xx/429/transport) but {@code max-attempts} were exhausted. */
        EXHAUSTED,
        /** The failure was a non-retryable {@code 4xx}; it was never going to succeed, so it was not retried. */
        FATAL
    }
}
