package com.jinternals.webhooks.consumer.idempotency;

import org.springframework.lang.Nullable;

/**
 * Duplicate detection on {@code webhook-id} (see SPEC.md §11). This is the <strong>extension point
 * (SPI)</strong> for idempotency: the shipped implementations are {@link NoOpIdempotencyGuard}
 * (default) and {@link InMemoryIdempotencyGuard} (opt-in, single-instance, best-effort).
 *
 * <p>The contract is <strong>reserve-then-commit</strong>, so a delivery is only remembered as a
 * duplicate once it has actually been processed — not merely received:
 * <ul>
 *   <li>{@link #tryBegin(String)} reserves the id and returns {@code true} when the delivery is new and
 *       should proceed, or {@code false} when it is already in flight or already processed (skip it).</li>
 *   <li>{@link #markProcessed(String)} commits the reservation after the delivery was handled, so future
 *       retries of the same id are suppressed.</li>
 *   <li>{@link #release(String)} drops a reservation when processing <em>failed</em>, so the sender's
 *       retry of the same id is allowed to run rather than being falsely treated as a duplicate.</li>
 * </ul>
 * This timing matters in synchronous mode: marking an id "seen" before the handler succeeds would, on a
 * handler failure, make the sender's retry a no-op {@code 204} and silently drop the event.
 *
 * <p>For a multi-instance deployment behind a load balancer, duplicates can reach different
 * instances, so in-memory dedupe is insufficient. Provide your own {@code @Bean IdempotencyGuard}
 * backed by a shared store (Redis, a database, DynamoDB, …); the auto-configuration backs off when a
 * bean is already present ({@code @ConditionalOnMissingBean}). The library ships no distributed
 * implementation by design.
 *
 * <p>Even a shared store is an optimisation, not a guarantee; real exactly-once still requires
 * idempotent handlers.
 */
public interface IdempotencyGuard {

    /**
     * Reserve this {@code webhookId} for processing. Returns {@code true} when the id is new (and is now
     * reserved, so the caller should proceed), or {@code false} when it has already been reserved or
     * processed (so the caller should skip it as a duplicate). A null/blank id is never a duplicate.
     */
    boolean tryBegin(@Nullable String webhookId);

    /**
     * Commit a reservation made by {@link #tryBegin(String)} once the delivery has been processed, so
     * future retries of the same id are suppressed.
     */
    void markProcessed(@Nullable String webhookId);

    /**
     * Release a reservation made by {@link #tryBegin(String)} when processing <em>failed</em>, so the
     * sender's retry of the same id is allowed to run instead of being skipped as a duplicate.
     */
    void release(@Nullable String webhookId);
}
