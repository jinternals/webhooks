package com.jinternals.webhooks.producer.annotations;

import com.jinternals.webhooks.producer.client.registrar.WebhookClientRegistrar;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Enables annotation-driven Standard Webhooks producer clients (see SPEC.md §19). Place on a
 * {@code @Configuration} class in the host application and tell it where your
 * {@link WebhookClient}-annotated interfaces are; the producer registers a proxy bean for each.
 *
 * <p>Three ways to select clients, mirroring {@code @EnableFeignClients}. They combine (the union of all
 * selected interfaces is registered, deduplicated):
 *
 * <ul>
 *   <li>{@link #clients()} — an explicit, refactor-safe list with <strong>no classpath scanning</strong>.
 *       The most predictable option and the best fit for this toolkit's drop-in philosophy:
 *       <pre>&#64;EnableWebhookClients(clients = {CrmWebhooks.class, BillingWebhooks.class})</pre></li>
 *   <li>{@link #basePackageClasses()} — type-safe package anchors (scan the packages those classes live
 *       in), so there is no magic string to drift:
 *       <pre>&#64;EnableWebhookClients(basePackageClasses = CrmWebhooks.class)</pre></li>
 *   <li>{@link #basePackages()} — string base packages to scan (Feign-style convenience):
 *       <pre>&#64;EnableWebhookClients(basePackages = "com.acme.webhooks")</pre></li>
 * </ul>
 *
 * <p>If none of the three is set, the annotated class's own package is scanned as a convenience default.
 *
 * <p>Opt-in by design: the library never component-scans on its own, so it can't pick up the host's
 * other beans or collide with its scanning (see SPEC.md §14). Prefer {@link #clients()} when you want
 * zero scanning at all.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(WebhookClientRegistrar.class)
public @interface EnableWebhookClients {

    /**
     * Explicit {@link WebhookClient}-annotated interfaces to register. Refactor-safe and incurs
     * <strong>no classpath scanning</strong>. Combined with any packages selected by
     * {@link #basePackages()}/{@link #basePackageClasses()}.
     */
    Class<?>[] clients() default {};

    /**
     * Type-safe anchors for packages to scan: the package of each listed class is scanned for
     * {@link WebhookClient} interfaces. Avoids the magic strings of {@link #basePackages()}.
     */
    Class<?>[] basePackageClasses() default {};

    /**
     * String base packages to scan for {@link WebhookClient} interfaces. If no selector at all is set
     * ({@link #clients()}, {@link #basePackageClasses()}, and this are all empty), the annotated class's
     * own package is scanned.
     */
    String[] basePackages() default {};
}
