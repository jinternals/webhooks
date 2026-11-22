package com.jinternals.webhooks.consumer.idempotency;

/**
 * Default guard: never treats anything as a duplicate (see SPEC.md §11). Relies on the Standard
 * Webhooks replay window for safety and on handlers being naturally idempotent. Reservation and commit
 * are no-ops — every delivery is allowed to proceed.
 */
public class NoOpIdempotencyGuard implements IdempotencyGuard {

    @Override
    public boolean tryBegin(String webhookId) {
        return true;
    }

    @Override
    public void markProcessed(String webhookId) {
        // no-op: nothing is remembered.
    }

    @Override
    public void release(String webhookId) {
        // no-op: nothing was reserved.
    }
}
