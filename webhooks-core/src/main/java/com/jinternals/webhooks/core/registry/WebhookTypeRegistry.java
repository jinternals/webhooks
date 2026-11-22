package com.jinternals.webhooks.core.registry;

import java.util.Map;

/**
 * Maps a Standard Webhooks {@code type} string to its {@code @WebhookType}-annotated Java class
 * (see SPEC.md §5). Adapted from {@code jinternals-event-bus}'s {@code EventUtils.getTypeMapping}.
 *
 * <p>This is a pure, immutable {@code type → Class} lookup with no discovery logic of its own: the core
 * module stays free of any classpath-scanning dependency. Building the map from annotated classes — whether
 * by scanning base packages or by an explicit list — is the responsibility of the module that owns a
 * scanner (the consumer's {@code WebhookTypeScanner}), keeping the scanning concern out of core.
 */
public class WebhookTypeRegistry {

    private final Map<String, Class<?>> byType;

    public WebhookTypeRegistry(Map<String, Class<?>> byType) {
        this.byType = Map.copyOf(byType);
    }

    /** The target class for an event type, or {@code null} if the type is unknown. */
    public Class<?> classOf(String type) {
        return byType.get(type);
    }

    public Map<String, Class<?>> asMap() {
        return byType;
    }
}
