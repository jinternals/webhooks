package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Sample reserve-then-commit {@link IdempotencyGuard} (see {@code docs/reference/beans-and-spis.md}).
 *
 * <p>Mirrors Redis {@code SET NX EX} ({@link #tryBegin}), {@code SET EX} ({@link #markProcessed}), and a
 * compare-and-delete Lua CAS script ({@link #release}) against a pluggable {@link KeyValueStore} so a stalled
 * caller whose reservation expired never deletes a sibling instance's {@code "processed"} state.
 */
public class SharedStoreIdempotencyGuard implements IdempotencyGuard {

    public interface KeyValueStore {
        boolean setIfAbsent(String key, String value, Duration ttl);

        void set(String key, String value, Duration ttl);

        boolean deleteIfValueMatches(String key, String expectedValue);
    }

    private record OwnedReservation(String webhookId, String token) {}

    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(2);
    private static final Duration PROCESSED_TTL = Duration.ofDays(1);

    private final KeyValueStore store;
    private final ThreadLocal<OwnedReservation> currentReservation = new ThreadLocal<>();

    public SharedStoreIdempotencyGuard(KeyValueStore store) {
        this.store = store;
    }

    @Override
    public boolean tryBegin(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            currentReservation.remove();
            return true;
        }
        OwnedReservation existing = currentReservation.get();
        if (existing != null && !webhookId.equals(existing.webhookId())) {
            currentReservation.remove();
        }
        String token = "in-flight:" + UUID.randomUUID();
        boolean acquired = store.setIfAbsent("wh:" + webhookId, token, IN_FLIGHT_TTL);
        if (acquired) {
            currentReservation.set(new OwnedReservation(webhookId, token));
        }
        return acquired;
    }

    @Override
    public void markProcessed(@Nullable String webhookId) {
        if (webhookId != null && !webhookId.isBlank()) {
            store.set("wh:" + webhookId, "processed", PROCESSED_TTL);
            OwnedReservation owned = currentReservation.get();
            if (owned != null && webhookId.equals(owned.webhookId())) {
                currentReservation.remove();
            }
        }
    }

    @Override
    public void release(@Nullable String webhookId) {
        OwnedReservation owned = currentReservation.get();
        currentReservation.remove();
        if (webhookId != null && !webhookId.isBlank() && owned != null && webhookId.equals(owned.webhookId())) {
            store.deleteIfValueMatches("wh:" + webhookId, owned.token());
        }
    }

    public static final class InMemoryKeyValueStore implements KeyValueStore {
        private static final int MAX_ENTRIES = 10_000;

        private record Entry(String value, Instant expiresAt) {}

        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            Instant now = Instant.now();
            lock.lock();
            try {
                Entry existing = entries.get(key);
                if (existing == null || !existing.expiresAt().isAfter(now)) {
                    entries.put(key, new Entry(value, now.plus(ttl)));
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            Instant now = Instant.now();
            lock.lock();
            try {
                entries.put(key, new Entry(value, now.plus(ttl)));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean deleteIfValueMatches(String key, String expectedValue) {
            lock.lock();
            try {
                Entry existing = entries.get(key);
                if (existing != null && expectedValue.equals(existing.value())) {
                    entries.remove(key);
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
