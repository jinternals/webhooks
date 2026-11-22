package com.jinternals.webhooks.consumer.config.typefixtures;

import com.jinternals.webhooks.core.annotations.WebhookType;

/**
 * A {@code @WebhookType} event under a dedicated fixture package, scanned by
 * {@code WebhookConsumerAutoConfigurationTest} to prove {@code webhookTypeRegistry} discovers event
 * classes from the host's auto-configuration packages (zero-config scanning).
 */
@WebhookType("sample.created")
public class SampleCreated {
    public String id;
}
