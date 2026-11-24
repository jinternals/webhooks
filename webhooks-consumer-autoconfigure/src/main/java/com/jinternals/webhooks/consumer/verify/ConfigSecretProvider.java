package com.jinternals.webhooks.consumer.verify;

import com.jinternals.webhooks.consumer.config.WebhookProperties.Endpoint;
import com.jinternals.webhooks.core.secret.SecretProvider;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Default consumer {@link SecretProvider}: resolves secrets from Spring configuration (see SPEC.md
 * §8a). Returns the endpoint's primary {@code secret} first, followed by any {@code additional-secrets}
 * declared for rotation. Blank entries are dropped so a half-configured rotation can't weaken
 * verification.
 */
public class ConfigSecretProvider implements SecretProvider<Endpoint> {

    @Override
    public List<String> secretsFor(Endpoint endpoint) {
        List<String> secrets = new ArrayList<>();
        addIfPresent(secrets, endpoint.getSecret());
        for (String extra : endpoint.getAdditionalSecrets()) {
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
