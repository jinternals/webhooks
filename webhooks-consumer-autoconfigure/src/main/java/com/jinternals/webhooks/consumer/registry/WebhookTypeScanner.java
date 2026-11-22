package com.jinternals.webhooks.consumer.registry;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.reflections.Reflections;

/**
 * Classpath scanner that discovers {@code @WebhookType}-annotated event classes and builds a
 * {@link WebhookTypeRegistry} (see SPEC.md §5, §14). The scanning concern — and its {@code reflections}
 * dependency — lives in the consumer module, not in {@code webhooks-core}, so core stays a pure wire-model
 * library with no classpath-scanning weight.
 */
public final class WebhookTypeScanner {

    private WebhookTypeScanner() {}

    /** Scan a single base package for {@code @WebhookType} classes and build the registry. */
    public static WebhookTypeRegistry scan(String basePackage) {
        Set<Class<?>> eventClasses = new Reflections(basePackage).getTypesAnnotatedWith(WebhookType.class);
        Map<String, Class<?>> byType = eventClasses.stream()
                .collect(toMap(cls -> cls.getAnnotation(WebhookType.class).value(), identity()));
        return new WebhookTypeRegistry(byType);
    }

    /**
     * Scan the union of several base packages for {@code @WebhookType} classes and build one registry. Used
     * for zero-config discovery, where the base packages are the host application's auto-configuration
     * packages (its {@code @SpringBootApplication} package and any additional {@code @AutoConfigurationPackage}
     * sources). Duplicate {@code type} strings across packages collide as a programming error, surfaced
     * eagerly rather than silently coalesced.
     */
    public static WebhookTypeRegistry scan(Collection<String> basePackages) {
        Map<String, Class<?>> byType = new LinkedHashMap<>();
        for (String basePackage : basePackages) {
            Set<Class<?>> eventClasses = new Reflections(basePackage).getTypesAnnotatedWith(WebhookType.class);
            for (Class<?> eventClass : eventClasses) {
                String type = eventClass.getAnnotation(WebhookType.class).value();
                Class<?> previous = byType.putIfAbsent(type, eventClass);
                if (previous != null && previous != eventClass) {
                    throw new IllegalStateException("Duplicate @WebhookType(\"" + type + "\") on " + previous.getName()
                            + " and " + eventClass.getName());
                }
            }
        }
        return new WebhookTypeRegistry(byType);
    }
}
