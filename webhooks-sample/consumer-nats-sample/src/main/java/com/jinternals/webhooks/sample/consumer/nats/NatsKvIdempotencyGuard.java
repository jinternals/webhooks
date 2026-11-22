package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Distributed, cross-instance {@link IdempotencyGuard} backed by a NATS JetStream {@link KeyValue} bucket.
 *
 * <ul>
 *   <li>{@link #tryBegin(String)} uses {@link KeyValue#create(String, byte[])} (atomic CAS on revision 0) so
 *       only one consumer instance reserves a {@code webhook-id};</li>
 *   <li>{@link #markProcessed(String)} updates the entry to {@code "processed"} after {@link DurableInboxHandlers}
 *       obtains a JetStream {@code PublishAck};</li>
 *   <li>{@link #release(String)} deletes the reservation if inbox persistence fails, allowing the sender's
 *       retry of the same {@code webhook-id} to succeed (zero webhook loss).</li>
 * </ul>
 */
@Component
public class NatsKvIdempotencyGuard implements IdempotencyGuard {

    private static final byte[] IN_FLIGHT = "in-flight".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROCESSED = "processed".getBytes(StandardCharsets.UTF_8);

    private final KeyValue keyValue;

    public NatsKvIdempotencyGuard(KeyValue keyValue) {
        this.keyValue = keyValue;
    }

    @Override
    public boolean tryBegin(@Nullable String webhookId) {
        if (webhookId == null || webhookId.isBlank()) {
            return true;
        }
        try {
            keyValue.create(encodeKey(webhookId), IN_FLIGHT);
            return true;
        } catch (JetStreamApiException alreadyExists) {
            // Key already exists (NATS error 10071 wrong last sequence): already in-flight or processed.
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reserve webhook-id in NATS KV: " + webhookId, e);
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
