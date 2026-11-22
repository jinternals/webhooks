package com.jinternals.webhooks.consumer.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.lang.Nullable;

/**
 * Best-effort, in-memory dedupe on {@code webhook-id} (see SPEC.md §11). Enabled with
 * {@code webhooks.consumer.in-memory-idempotency: true}.
 *
 * <p>Catches duplicate retries within one instance's lifetime; it does <strong>not</strong> survive
 * restarts or coordinate across instances, and is no substitute for idempotent handlers.
 *
 * <p><strong>Reserve-then-commit.</strong> An id is recorded on {@link #tryBegin(String) tryBegin} as
 * <em>reserved</em>, so a concurrent duplicate is skipped while the first is still in flight. On success
 * the caller {@link #markProcessed(String) commits} it (kept until the TTL expires), so later retries are
 * suppressed; on failure the caller {@link #release(String) releases} it, so the sender's retry runs
 * instead of being falsely deduplicated.
 *
 * <p><strong>Hardening (SPEC.md §18).</strong> The key is the inbound {@code webhook-id} — i.e.
 * attacker-influenced data (though only reached <em>after</em> signature verification). Two safeguards
 * keep it from becoming a memory sink:
 * <ul>
 *   <li><strong>Hard size cap</strong> ({@code maxSize}). Built on a {@code LinkedHashMap} in
 *       access-order with {@code removeEldestEntry}, so once full it evicts the least-recently-seen id
 *       in O(1). A flood of unique ids can never grow it past the cap.</li>
 *   <li><strong>TTL expiry</strong> aligned to the Standard Webhooks replay window: an id older than
 *       the TTL is treated as new, so stale entries never cause a false "duplicate".</li>
 * </ul>
 *
 * <p>Guarded by a {@link ReentrantLock} rather than {@code synchronized} so virtual threads
 * ({@code spring.threads.virtual.enabled=true}) never pin carrier threads under contention on Java 21.
 */
public class InMemoryIdempotencyGuard implements IdempotencyGuard {

    /** Retain ids slightly longer than the replay window so retries inside it are caught. */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** Default upper bound on retained ids; override via the constructor. */
    static final int DEFAULT_MAX_SIZE = 100_000;

    final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Instant> seen;
    private final Set<String> committed = new HashSet<>();
    private final Clock clock;

    public InMemoryIdempotencyGuard() {
        this(DEFAULT_MAX_SIZE, Clock.systemUTC());
    }

    public InMemoryIdempotencyGuard(int maxSize) {
        this(maxSize, Clock.systemUTC());
    }

    public InMemoryIdempotencyGuard(int maxSize, Clock clock) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("idempotency maxSize must be >= 1, was " + maxSize);
        }
        this.clock = clock;
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                if (size() > maxSize) {
                    committed.remove(eldest.getKey());
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public boolean tryBegin(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return true;
        }
        Instant now = clock.instant();
        Instant nextExpiry = now.plus(TTL);
        boolean accepted;
        lock.lock();
        try {
            Instant previousExpiry = seen.put(webhookId, nextExpiry);
            accepted = previousExpiry == null || !previousExpiry.isAfter(now);
            if (accepted) {
                committed.remove(webhookId);
            }
        } finally {
            lock.unlock();
        }
        return accepted;
    }

    @Override
    public void markProcessed(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        // Refresh the TTL from completion time so a slow handler can't let the entry expire mid-flight.
        Instant expiry = clock.instant().plus(TTL);
        lock.lock();
        try {
            seen.put(webhookId, expiry);
            committed.add(webhookId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        lock.lock();
        try {
            if (!committed.contains(webhookId)) {
                seen.remove(webhookId);
            }
        } finally {
            lock.unlock();
        }
    }
}
