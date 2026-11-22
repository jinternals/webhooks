package com.jinternals.webhooks.sample.spi;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        private record Entry(String value, Instant expiresAt) {}

        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            Instant now = Instant.now();
            boolean[] inserted = {false};
            entries.compute(key, (k, existing) -> {
                if (existing == null || !existing.expiresAt().isAfter(now)) {
                    inserted[0] = true;
                    return new Entry(value, now.plus(ttl));
                }
                return existing;
            });
            return inserted[0];
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            entries.put(key, new Entry(value, Instant.now().plus(ttl)));
        }

        @Override
        public void delete(String key) {
            entries.remove(key);
        }
    }
}
