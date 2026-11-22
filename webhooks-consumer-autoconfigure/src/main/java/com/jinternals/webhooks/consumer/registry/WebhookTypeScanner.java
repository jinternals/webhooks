package com.jinternals.webhooks.consumer.registry;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Classpath scanner that discovers {@code @WebhookType}-annotated event classes and builds a
 * {@link WebhookTypeRegistry} (see SPEC.md §5, §14). The scanning concern lives in the consumer module, not in
 * {@code webhooks-core}, so core stays a pure wire-model library with no classpath-scanning weight.
 *
 * <p>Scanning uses Spring's {@link ClassPathScanningCandidateComponentProvider}, which reads class metadata with
 * ASM (without loading every class on the classpath) and only loads the matching classes. A class is discovered
 * when it is <em>directly</em> annotated with {@code @WebhookType} and is concrete and independent (top-level or
 * {@code static} nested) — the only kinds of class Jackson can instantiate as an event payload. Base packages
 * include their sub-packages.
 */
public final class WebhookTypeScanner {

    private WebhookTypeScanner() {}

    /** Scan a single base package (and its sub-packages) for {@code @WebhookType} classes and build the registry. */
    public static WebhookTypeRegistry scan(String basePackage) {
        return scan(List.of(basePackage));
    }

    /**
     * Scan the union of several base packages for {@code @WebhookType} classes and build one registry. Used
     * for zero-config discovery, where the base packages are the host application's auto-configuration
     * packages (its {@code @SpringBootApplication} package and any additional {@code @AutoConfigurationPackage}
     * sources). Duplicate {@code type} strings across packages collide as a programming error, surfaced
     * eagerly rather than silently coalesced.
     */
    public static WebhookTypeRegistry scan(Collection<String> basePackages) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        // considerMetaAnnotations=false: only classes carrying @WebhookType itself (the type string is read
        // from that annotation below).
        scanner.addIncludeFilter(new AnnotationTypeFilter(WebhookType.class, false));
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

        Map<String, Class<?>> byType = new LinkedHashMap<>();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> eventClass = ClassUtils.resolveClassName(candidate.getBeanClassName(), classLoader);
                String type = eventClass.getAnnotation(WebhookType.class).value();
                if (type.isBlank()) {
                    throw new IllegalStateException("@WebhookType value must not be blank on " + eventClass.getName());
                }
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
