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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final Logger log = LoggerFactory.getLogger(NatsKvIdempotencyGuard.class);

    /** NATS JetStream error code 10071: {@code wrong last sequence} (optimistic CAS revision mismatch). */
    static final int JS_WRONG_LAST_SEQUENCE = 10071;

    private static final String STATE_PROCESSED = "processed";
    private static final byte[] IN_FLIGHT = "in-flight".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROCESSED = STATE_PROCESSED.getBytes(StandardCharsets.UTF_8);

    private record OwnedLease(String webhookId, String encodedKey, long revision) {}

    private final KeyValue keyValue;
    private final NatsConsumerProperties properties;
    private final Clock clock;
    private final ThreadLocal<OwnedLease> currentLease = new ThreadLocal<>();

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
            long rev = keyValue.create(key, IN_FLIGHT);
            currentLease.set(new OwnedLease(webhookId, key, rev));
            return true;
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == JS_WRONG_LAST_SEQUENCE) {
                return resolveExistingEntry(key, webhookId);
            }
            throw new IllegalStateException("Failed to reserve webhook-id in NATS KV: " + webhookId, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reserve webhook-id in NATS KV: " + webhookId, e);
        }
    }

    private boolean resolveExistingEntry(String key, String webhookId) {
        try {
            KeyValueEntry entry = keyValue.get(key);
            if (entry == null) {
                long rev = keyValue.create(key, IN_FLIGHT);
                currentLease.set(new OwnedLease(webhookId, key, rev));
                return true;
            }
            if (STATE_PROCESSED.equals(entry.getValueAsString())) {
                return false;
            }
            Duration age = Duration.between(entry.getCreated().toInstant(), clock.instant());
            if (age.compareTo(properties.getInFlightLease()) > 0) {
                long rev = keyValue.update(key, IN_FLIGHT, entry.getRevision());
                currentLease.set(new OwnedLease(webhookId, key, rev));
                return true;
            }
            throw new IllegalStateException(
                    "Webhook " + webhookId + " is currently in-flight on another consumer instance");
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == JS_WRONG_LAST_SEQUENCE) {
                throw new IllegalStateException(
                        "Concurrent in-flight contention for webhook " + webhookId + "; retry", e);
            }
            throw new IllegalStateException("Failed to inspect/reclaim webhook-id in NATS KV: " + webhookId, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect webhook-id in NATS KV: " + webhookId, e);
        }
    }

    @Override
    public void markProcessed(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        OwnedLease lease = currentLease.get();
        String key = (lease != null && webhookId.equals(lease.webhookId())) ? lease.encodedKey() : encodeKey(webhookId);
        try {
            keyValue.put(key, PROCESSED);
            // Clear only AFTER put() succeeds: if put() throws, WebhookReceivePipeline invokes release(),
            // which must still see ownedRevision so it runs a revision-checked CAS delete rather than an
            // unconditional delete that could wipe a sibling pod's reclaimed lease.
            if (lease != null && webhookId.equals(lease.webhookId())) {
                currentLease.remove();
            }
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException("Failed to mark webhook-id processed in NATS KV: " + webhookId, e);
        }
    }

    @Override
    public void release(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return;
        }
        OwnedLease lease = currentLease.get();
        boolean matchedLease = lease != null && webhookId.equals(lease.webhookId());
        String key = matchedLease ? lease.encodedKey() : encodeKey(webhookId);
        Long ownedRevision = matchedLease ? lease.revision() : null;
        if (matchedLease) {
            currentLease.remove();
        }
        try {
            if (ownedRevision != null) {
                // Revision-checked CAS delete: never wipe out a sibling pod's reclaimed lease or "processed" state!
                keyValue.delete(key, ownedRevision);
            } else {
                keyValue.delete(key);
            }
        } catch (JetStreamApiException e) {
            if (ownedRevision != null && e.getApiErrorCode() == JS_WRONG_LAST_SEQUENCE) {
                log.info("Skipping stale idempotency release for webhook-id={} (lease already reclaimed)", webhookId);
                return;
            }
            throw new IllegalStateException("Failed to release webhook-id in NATS KV: " + webhookId, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to release webhook-id in NATS KV: " + webhookId, e);
        }
    }

    /** Base64url-encode arbitrary webhook-id characters so every ID is a valid NATS KV key token. */
    static String encodeKey(String webhookId) {
        return "wh_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(webhookId.getBytes(StandardCharsets.UTF_8));
    }
}
