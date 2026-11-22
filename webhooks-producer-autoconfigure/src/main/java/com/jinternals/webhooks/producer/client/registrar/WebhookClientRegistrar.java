package com.jinternals.webhooks.producer.client.registrar;

import com.jinternals.webhooks.core.annotations.WebhookType;
import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.annotations.WebhookClient;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Discovers {@code @WebhookClient} interfaces and registers a {@link WebhookClientFactoryBean} for each
 * (see SPEC.md §19). Triggered by {@link EnableWebhookClients}, it honours all three selectors, taking
 * the <em>union</em> of the interfaces they resolve (deduplicated by class name):
 *
 * <ul>
 *   <li>{@code clients} — explicit interface classes, registered directly with no scanning;</li>
 *   <li>{@code basePackageClasses} — scan the package each anchor class lives in;</li>
 *   <li>{@code basePackages} — scan the given package strings.</li>
 * </ul>
 *
 * <p>If no selector is set, the annotated class's own package is scanned as a convenience default.
 *
 * <p>This is Spring-wiring glue (bean-definition registration); its behavior is verified by the
 * producer's {@code @SpringBootTest} context test rather than by mutation testing.
 */
public class WebhookClientRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(EnableWebhookClients.class.getName());

        // LinkedHashSet: deterministic order, deduplicated across selectors.
        Set<String> clientClassNames = new LinkedHashSet<>();

        // 1. Explicit client classes — no scanning.
        for (Class<?> client : attribute(attributes, "clients")) {
            clientClassNames.add(client.getName());
        }

        // 2 + 3. Scan packages resolved from basePackageClasses anchors and basePackages strings. When no
        // package is resolved the loop is a no-op, so no separate emptiness guard is needed.
        ClassPathScanningCandidateComponentProvider scanner = clientScanner();
        for (String basePackage : resolveBasePackages(metadata, attributes)) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                clientClassNames.add(candidate.getBeanClassName());
            }
        }

        for (String className : clientClassNames) {
            registerClient(registry, className);
        }
    }

    /** The scan targets: anchors' packages + string packages; or the annotated class's package if none set. */
    static Set<String> resolveBasePackages(AnnotationMetadata metadata, Map<String, Object> attributes) {
        Set<String> packages = new LinkedHashSet<>();
        for (Class<?> anchor : attribute(attributes, "basePackageClasses")) {
            packages.add(anchor.getPackageName());
        }
        for (String basePackage : stringAttribute(attributes, "basePackages")) {
            packages.add(basePackage);
        }
        boolean nothingSelected = packages.isEmpty() && attribute(attributes, "clients").length == 0;
        if (nothingSelected) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }

    private ClassPathScanningCandidateComponentProvider clientScanner() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                // A @WebhookClient is an interface; the scanner's default rejects interfaces, so we
                // accept exactly the interface candidates (a @WebhookClient on a class is ignored).
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(WebhookClient.class));
        return scanner;
    }

    /**
     * Eagerly validate a {@code @WebhookClient} interface at registration time (startup), so a malformed
     * client fails fast rather than at first call: first the type (it must be an interface, carry
     * {@code @WebhookClient}, and declare a non-blank destination), then every webhook method's signature.
     */
    // Package-private (not private) so it is directly unit-testable with clean mutation-coverage
    // attribution, independent of the surrounding bean-definition registration flow.
    static void validateClient(Class<?> clientInterface) {
        validateClientInterface(clientInterface);
        validateClientMethods(clientInterface);
    }

    /**
     * Validate the client <em>type</em> itself: it must be an {@code interface}, be annotated with
     * {@code @WebhookClient}, and declare a non-blank {@code destination}. This fails fast at registration
     * so a {@link WebhookClientFactoryBean} is never built for a type whose missing annotation or blank
     * destination would otherwise NPE (or publish to nowhere) at first use (see SPEC.md §19).
     */
    static void validateClientInterface(Class<?> clientInterface) {
        if (!clientInterface.isInterface()) {
            throw new InvalidWebhookClientException(
                    "@WebhookClient " + clientInterface.getName() + " must be an interface.");
        }
        WebhookClient annotation = clientInterface.getAnnotation(WebhookClient.class);
        if (annotation == null) {
            throw new InvalidWebhookClientException("@WebhookClient interface " + clientInterface.getName()
                    + " is missing the @WebhookClient " + "annotation.");
        }
        if (annotation.destination().isBlank()) {
            throw new InvalidWebhookClientException("@WebhookClient interface " + clientInterface.getName()
                    + " must declare a non-blank " + "destination.");
        }
    }

    /**
     * Eagerly validate every webhook method of a {@code @WebhookClient} interface. Each must take exactly one
     * {@code @WebhookType}-annotated event parameter and return {@code void}: a webhook publish is fire-and-forget,
     * its success/failure surfaced by a thrown exception (see SPEC.md §19), so there is no value to return —
     * a non-void method would always yield {@code null} (and a primitive return would fail with an NPE).
     * This mirrors the consumer's eager {@code @WebhookHandler} signature validation.
     *
     * <p>Iterates {@link Class#getMethods()} (not just {@code getDeclaredMethods()}) so inherited abstract
     * webhook methods from super-interfaces are validated too, deduplicating by signature so a method an
     * override redeclares is not validated twice.
     */
    // Package-private (not private) so it is directly unit-testable with clean mutation-coverage
    // attribution, independent of the surrounding bean-definition registration flow.
    //
    // Returns the number of webhook methods actually validated. This count is the observable signal that
    // both the signature identity AND the dedup guard did their jobs: a method an override surfaces twice
    // via getMethods() is validated exactly once (so dropping the seen.add() guard inflates the count), and
    // two genuinely distinct methods are validated twice (so collapsing signature identity deflates it).
    static int validateClientMethods(Class<?> clientInterface) {
        Set<List<Object>> seen = new LinkedHashSet<>();
        int validated = 0;
        for (Method method : clientInterface.getMethods()) {
            if (isWebhookMethod(method) && seen.add(signature(method))) {
                validateWebhookMethod(method);
                validated++;
            }
        }
        return validated;
    }

    // Name + parameter types uniquely identify a method for dedup: getMethods() can surface the same
    // webhook method via both a super-interface and an override, and we must validate it only once.
    private static List<Object> signature(Method method) {
        return List.of(method.getName(), List.of(method.getParameterTypes()));
    }

    /**
     * A method is a webhook publish method (and so subject to signature validation) when it is an abstract
     * method the host declared on the client interface — i.e. not {@code default}, not {@code static}, and
     * not compiler- or tooling-{@code synthetic}. The {@code synthetic} exclusion is load-bearing, not
     * redundant: covariant bridge methods are synthetic, and coverage instrumentation (e.g. JaCoCo's
     * {@code $jacocoInit}) adds synthetic methods to interfaces that are not {@code default}, so both
     * guards are needed. A {@code static} interface method has a body (like a {@code default} one) and is
     * never a publish method, so it is excluded too.
     */
    static boolean isWebhookMethod(Method method) {
        if (method.isSynthetic()) {
            return false;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        return !method.isDefault();
    }

    private static void validateWebhookMethod(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1) {
            throw new InvalidWebhookClientException(
                    method,
                    "must take exactly one @WebhookType-annotated event argument (found " + params.length + ").");
        }
        if (!params[0].isAnnotationPresent(WebhookType.class)) {
            throw new InvalidWebhookClientException(
                    method, "parameter " + params[0].getName() + " is not annotated with @WebhookType.");
        }
        if (method.getReturnType() != void.class) {
            throw new InvalidWebhookClientException(
                    method,
                    "must return void (a webhook publish is fire-and-forget; failures are thrown, not "
                            + "returned), but returns " + method.getReturnType().getName() + ".");
        }
    }

    // When @EnableWebhookClients is absent, metadata.getAnnotationAttributes(...) returns null; otherwise
    // Spring always populates every attribute with its (possibly empty) default array, so a present map
    // never holds a null value for a declared attribute.
    private static Class<?>[] attribute(Map<String, Object> attributes, String name) {
        if (attributes == null) {
            return new Class<?>[0];
        }
        return (Class<?>[]) attributes.get(name);
    }

    private static String[] stringAttribute(Map<String, Object> attributes, String name) {
        if (attributes == null) {
            return new String[0];
        }
        return (String[]) attributes.get(name);
    }

    private void registerClient(BeanDefinitionRegistry registry, String className) {
        if (registry.containsBeanDefinition(className)) {
            return;
        }
        Class<?> clientInterface = loadClient(className);
        validateClient(clientInterface);
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(WebhookClientFactoryBean.class);
        builder.addConstructorArgValue(clientInterface);
        // Lazy: the proxy is created only on first use, after all auto-configuration singletons (notably
        // the SecretProvider<Destination> and WebhookPublisher beans) exist. Without this a consumer of the
        // client (e.g. a web controller, or the metrics post-processor inspecting bean types) can force the
        // proxy — and thus the WebhookPublisher — to be built mid-startup, before the generically-typed
        // SecretProvider<Destination> bean is resolvable, failing with NoSuchBeanDefinitionException.
        builder.setLazyInit(true);
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        // The FactoryBean's product type, so type-based bean matching (metrics/actuator auto-configuration
        // inspecting bean types) can resolve the client WITHOUT instantiating the proxy. This must be the
        // Class, not its name: Spring's bean-definition type resolution rejects a String value here
        // ("Invalid value type for attribute 'factoryBeanObjectType'"), which otherwise breaks startup as
        // soon as spring-boot-starter-actuator is on the classpath.
        beanDefinition.setAttribute("factoryBeanObjectType", clientInterface);
        registry.registerBeanDefinition(className, beanDefinition);
    }

    /**
     * Load a discovered client interface by name. In normal operation the name always resolves (it came
     * from an already-loaded {@code Class} or from classpath scanning), so the failure path is defensive;
     * package-private to keep it directly testable.
     */
    static Class<?> loadClient(String className) {
        try {
            return ClassUtils.forName(className, ClassUtils.getDefaultClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load @WebhookClient interface " + className, e);
        }
    }
}
