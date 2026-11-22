package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.lang.Nullable;

/**
 * Sample reserve-then-commit {@link IdempotencyGuard} (see {@code docs/reference/beans-and-spis.md}).
 *
 * <p>Mirrors Redis {@code SET NX EX} ({@link #tryBegin}), {@code SET EX} ({@link #markProcessed}), and
 * {@code DEL} ({@link #release}) semantics against a pluggable {@link KeyValueStore}.
 */
public class SharedStoreIdempotencyGuard implements IdempotencyGuard {

    public interface KeyValueStore {
        boolean setIfAbsent(String key, String value, Duration ttl);

        void set(String key, String value, Duration ttl);

        void delete(String key);
    }

    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(2);
    private static final Duration PROCESSED_TTL = Duration.ofDays(1);

    private final KeyValueStore store;

    public SharedStoreIdempotencyGuard(KeyValueStore store) {
        this.store = store;
    }

    @Override
    public boolean tryBegin(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return true;
        }
        return store.setIfAbsent("wh:" + webhookId, "in-flight", IN_FLIGHT_TTL);
    }

    @Override
    public void markProcessed(@Nullable String webhookId) {
        if (webhookId != null && !webhookId.isBlank()) {
            store.set("wh:" + webhookId, "processed", PROCESSED_TTL);
        }
    }

    @Override
    public void release(@Nullable String webhookId) {
        if (webhookId != null && !webhookId.isBlank()) {
            store.delete("wh:" + webhookId);
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
        public void delete(String key) {
            lock.lock();
            try {
                entries.remove(key);
            } finally {
                lock.unlock();
            }
        }
    }
}
