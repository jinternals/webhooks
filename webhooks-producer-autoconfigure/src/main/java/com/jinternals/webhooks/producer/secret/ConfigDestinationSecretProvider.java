package com.jinternals.webhooks.producer.secret;

import com.jinternals.webhooks.core.secret.SecretProvider;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties.Destination;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Default producer {@link SecretProvider}: resolves the signing secret(s) from the destination's Spring
 * configuration (see SPEC.md §19, §19a). Returns the primary {@code secret} first, followed by any
 * {@code additional-secrets} declared for zero-downtime rotation, so the publisher can dual-sign. Blank
 * entries are dropped, and an all-blank destination yields an empty list so signing fails fast rather
 * than producing an unsigned delivery.
 *
 * <p>Override with a {@code @Bean SecretProvider<Destination>} (or {@code @Bean("destinationSecretProvider")})
 * to pull the secret from a secrets manager instead.
 */
public class ConfigDestinationSecretProvider implements SecretProvider<Destination> {

    @Override
    public List<String> secretsFor(Destination destination) {
        List<String> secrets = new ArrayList<>();
        addIfPresent(secrets, destination.getSecret());
        for (String extra : destination.getAdditionalSecrets()) {
            addIfPresent(secrets, extra);
        }
        return secrets;
    }

    private static void addIfPresent(List<String> secrets, @Nullable String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }
}
