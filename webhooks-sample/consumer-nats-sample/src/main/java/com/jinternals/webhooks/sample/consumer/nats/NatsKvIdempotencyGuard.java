package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Multi-instance-safe {@link IdempotencyGuard} backed by a NATS JetStream {@link KeyValue} bucket:
 *
 * <ul>
 *   <li>{@link #tryBegin(String)} performs an atomic {@code kv.create(key, "in-flight")} (CAS on revision 0).</li>
 *   <li>If the key already exists:
 *       <ul>
 *         <li>{@code "processed"} -&gt; returns {@code false} ({@code 204} duplicate suppressed);</li>
 *         <li>{@code "in-flight"} older than {@code in-flight-lease} (sibling pod crashed via {@code kill -9}
 *             before persisting to {@code WEBHOOKS_INBOUND}) -&gt; atomically reclaims the lease via
 *             {@code kv.update(key, "in-flight", entry.getRevision())} (CAS so only one pod wins!) and
 *             returns {@code true};</li>
 *         <li>{@code "in-flight"} still active -&gt; throws a retryable {@code 500} (without calling
 *             {@link #release}) so the sender's NATS relay retries cleanly rather than receiving a false
 *             {@code 204} before persistence completes.</li>
 *       </ul>
 *   </li>
 *   <li>{@link #markProcessed(String)} writes {@code "processed"} after {@link DurableInboxHandlers} receives
 *       its JetStream {@code PublishAck}.</li>
 *   <li>{@link #release(String)} deletes the reservation when inbox persistence throws so the sender's retry
 *       succeeds immediately.</li>
 * </ul>
 */
@Component
public class NatsKvIdempotencyGuard implements IdempotencyGuard {

    private static final String STATE_PROCESSED = "processed";
    private static final byte[] IN_FLIGHT = "in-flight".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROCESSED = STATE_PROCESSED.getBytes(StandardCharsets.UTF_8);

    private final KeyValue keyValue;
    private final NatsConsumerProperties properties;
    private final Clock clock;

    public NatsKvIdempotencyGuard(
            KeyValue keyValue, NatsConsumerProperties properties, ObjectProvider<Clock> clockProvider) {
        this.keyValue = keyValue;
        this.properties = properties;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public boolean tryBegin(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return true;
        }
        String key = encodeKey(webhookId);
        try {
            keyValue.create(key, IN_FLIGHT);
            return true;
        } catch (JetStreamApiException alreadyExists) {
            return resolveExistingEntry(key, webhookId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reserve webhook-id in NATS KV: " + webhookId, e);
        }
    }

    private boolean resolveExistingEntry(String key, String webhookId) {
        try {
            KeyValueEntry entry = keyValue.get(key);
            if (entry == null) {
                keyValue.create(key, IN_FLIGHT);
                return true;
            }
            if (STATE_PROCESSED.equals(entry.getValueAsString())) {
                return false;
            }
            Duration age = Duration.between(entry.getCreated().toInstant(), clock.instant());
            if (age.compareTo(properties.getInFlightLease()) > 0) {
                keyValue.update(key, IN_FLIGHT, entry.getRevision());
                return true;
            }
            throw new IllegalStateException(
                    "Webhook " + webhookId + " is currently in-flight on another consumer instance");
        } catch (JetStreamApiException casLost) {
            throw new IllegalStateException(
                    "Concurrent in-flight contention for webhook " + webhookId + "; retry", casLost);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect webhook-id in NATS KV: " + webhookId, e);
        }
    }

    @Override
    public void markProcessed(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        try {
            keyValue.put(encodeKey(webhookId), PROCESSED);
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException("Failed to mark webhook-id processed in NATS KV: " + webhookId, e);
        }
    }

    @Override
    public void release(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        try {
            keyValue.delete(encodeKey(webhookId));
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException("Failed to release webhook-id in NATS KV: " + webhookId, e);
        }
    }

    /** Base64url-encode arbitrary webhook-id characters so every ID is a valid NATS KV key token. */
    static String encodeKey(String webhookId) {
        return "wh_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(webhookId.getBytes(StandardCharsets.UTF_8));
    }
}
