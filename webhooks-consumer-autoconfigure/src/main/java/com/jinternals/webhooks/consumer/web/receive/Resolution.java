package com.jinternals.webhooks.consumer.web.receive;

/**
 * The outcome of resolving a verified envelope to an event (see {@link EventResolver}): either the type
 * is {@link Accepted} — modelled by a {@code @WebhookType} class, strictly deserialized and validated —
 * or it is {@link Ignored} (unaccepted/unmodelled under the {@code ACK_AND_IGNORE} policy, so the caller
 * returns {@code 204}). The {@code REJECT} policy never yields a {@code Resolution}; it throws.
 *
 * <p>A sealed type (rather than a bare {@code Optional}) so the two acceptance outcomes are explicit and
 * the caller must handle both — "no event" is never conflated with "absent/null". {@link Accepted}
 * carries the resolved {@code type} alongside the event so downstream stages (metadata, observability,
 * dispatch) read it from the resolution rather than reaching back into the raw envelope.
 */
public sealed interface Resolution permits Resolution.Accepted, Resolution.Ignored {

    /** The type is modelled and the deserialized, validated {@code event} is ready to dispatch. */
    record Accepted(String type, Object event) implements Resolution {}

    /** The type is unaccepted or unmodelled; acknowledge and ignore (no dispatch). */
    record Ignored() implements Resolution {

        private static final Ignored INSTANCE = new Ignored();

        static Ignored instance() {
            return INSTANCE;
        }
    }

    static Resolution accepted(String type, Object event) {
        return new Accepted(type, event);
    }

    static Resolution ignored() {
        return Ignored.instance();
    }
}
