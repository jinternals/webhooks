package com.jinternals.webhooks.sample.consumer.spi;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sample {@link IdempotencyGuard} SPI implementation demonstrating the three-method
 * <strong>reserve-then-commit</strong> contract (see {@code docs/reference/beans-and-spis.md}).
 *
 * <p>In production with Spring Data Redis ({@code StringRedisTemplate}), implement {@link KeyValueStore} with:
 * <pre>{@code
 * setIfAbsent -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl))
 * set         -> redis.opsForValue().set(key, value, ttl)
 * delete      -> redis.delete(key)
 * }</pre>
 */
public class SharedStoreIdempotencyGuard implements IdempotencyGuard {

    /** Minimal key-value store port mirroring Redis {@code SET NX EX} / {@code SET EX} / {@code DEL}. */
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
    public boolean tryBegin(String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return true;
        }
        return store.setIfAbsent("wh:" + webhookId, "in-flight", IN_FLIGHT_TTL);
    }

    @Override
    public void markProcessed(String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        store.set("wh:" + webhookId, "processed", PROCESSED_TTL);
    }

    @Override
    public void release(String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        store.delete("wh:" + webhookId);
    }

    /** Simple in-memory {@link KeyValueStore} used by the sample profile and tests without requiring Redis. */
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
