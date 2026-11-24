package com.jinternals.webhooks.sample.spi.nullsafety;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.core.redact.WebhookRedactor;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import com.jinternals.webhooks.producer.transport.DeliveryFailure;
import com.jinternals.webhooks.producer.transport.SafeUrl;
import org.jspecify.annotations.Nullable;

/**
 * Compile-time JSpecify verification helper inside a {@code @NullMarked} package.
 *
 * <p>Compiled with Error Prone + NullAway ({@code -XepOpt:NullAway:JSpecifyMode=true}) against the published
 * {@code webhooks-core}, {@code webhooks-consumer-autoconfigure}, and {@code webhooks-producer-autoconfigure}
 * bytecode so any broken {@code @NullMarked} or {@code @Nullable} signature in the libraries fails sample
 * compilation immediately.
 */
public final class NullSafeWebhookInspector {

    private NullSafeWebhookInspector() {}

    /** Exercises {@link WebhookMetadata} non-null and {@code @Nullable} record components. */
    public static String summarizeMetadata(WebhookMetadata metadata) {
        String id = metadata.webhookId() != null ? metadata.webhookId() : "<unsigned>";
        String ip = metadata.sourceIp() != null ? metadata.sourceIp() : "<unknown-ip>";
        @Nullable String tenantHeader = metadata.headers().get("x-tenant-id");
        String tenant = tenantHeader != null ? tenantHeader : "<default-tenant>";
        return metadata.type()
                + "|"
                + metadata.endpointPath()
                + "|"
                + id
                + "|"
                + ip
                + "|"
                + tenant
                + "|"
                + metadata.redactedEvent();
    }

    /** Exercises {@link Envelope}, {@link WebhookTypeRegistry}, and {@link WebhookRedactor} nullness contracts. */
    public static @Nullable Class<?> resolveEnvelopeTarget(Envelope envelope, WebhookTypeRegistry registry) {
        @Nullable String type = envelope.getType();
        return registry.classOf(type);
    }

    /** Exercises {@link SafeUrl} non-null {@code sanitize} vs {@code @Nullable} {@code redactMessage}. */
    public static String describeDeadLetter(DeliveryFailure failure) {
        String safeTarget = SafeUrl.sanitize(failure.url());
        @Nullable String redactedCause = SafeUrl.redactMessage(failure.cause().getMessage(), failure.url());
        return failure.reason().name()
                + ":"
                + safeTarget
                + ":"
                + (redactedCause != null ? redactedCause : "<no-message>");
    }

    /** Exercises {@link IdempotencyGuard} accepting {@code @Nullable} {@code webhookId} and {@link WebhookRedactor}. */
    public static String redactNullablePayload(@Nullable Object payload, IdempotencyGuard guard, @Nullable String id) {
        if (guard.tryBegin(id)) {
            guard.markProcessed(id);
        }
        return WebhookRedactor.toRedactedString(payload);
    }
}
