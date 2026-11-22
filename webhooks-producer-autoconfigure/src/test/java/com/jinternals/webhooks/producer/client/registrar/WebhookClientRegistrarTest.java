package com.jinternals.webhooks.producer.client.registrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;
import com.jinternals.webhooks.producer.annotations.WebhookClient;
import com.jinternals.webhooks.producer.fixtures.explicit.ExplicitCrmWebhooks;
import com.jinternals.webhooks.producer.fixtures.scan.ScanCrmWebhooks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Direct unit test of {@link WebhookClientRegistrar#registerBeanDefinitions}, exercising all three
 * selectors ({@code clients}, {@code basePackageClasses}, {@code basePackages}), the union/dedup across
 * them, the "no selector -> scan the annotated class's package" default, and the exact bean definition
 * produced for each discovered interface (SPEC.md §19). Registration is driven against a real
 * {@link DefaultListableBeanFactory} so we can assert the registered definitions precisely.
 */
class WebhookClientRegistrarTest {

    private final WebhookClientRegistrar registrar = new WebhookClientRegistrar();

    private DefaultListableBeanFactory register(Class<?> configClass) {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        registrar.registerBeanDefinitions(AnnotationMetadata.introspect(configClass), registry);
        return registry;
    }

    private void assertClientBeanDefinition(DefaultListableBeanFactory registry, Class<?> clientInterface) {
        BeanDefinition definition = registry.getBeanDefinition(clientInterface.getName());
        assertThat(definition.getBeanClassName()).isEqualTo(WebhookClientFactoryBean.class.getName());
        assertThat(definition
                        .getConstructorArgumentValues()
                        .getIndexedArgumentValue(0, Class.class)
                        .getValue())
                .isEqualTo(clientInterface);
        assertThat(definition.isLazyInit()).as("client proxy must be lazy").isTrue();
        assertThat(((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                        .getAttribute("factoryBeanObjectType"))
                .isEqualTo(clientInterface);
    }

    @EnableWebhookClients(clients = ExplicitCrmWebhooks.class)
    static class ExplicitConfig {}

    @Test
    void registersExplicitClientsWithoutScanning() {
        DefaultListableBeanFactory registry = register(ExplicitConfig.class);

        assertThat(registry.getBeanDefinitionNames()).containsExactly(ExplicitCrmWebhooks.class.getName());
        assertClientBeanDefinition(registry, ExplicitCrmWebhooks.class);
    }

    @EnableWebhookClients(basePackages = "com.jinternals.webhooks.producer.fixtures.scan")
    static class BasePackagesConfig {}

    @Test
    void scansBasePackagesStrings() {
        DefaultListableBeanFactory registry = register(BasePackagesConfig.class);

        assertThat(registry.getBeanDefinitionNames()).containsExactly(ScanCrmWebhooks.class.getName());
        assertClientBeanDefinition(registry, ScanCrmWebhooks.class);
    }

    @EnableWebhookClients(basePackageClasses = ScanCrmWebhooks.class)
    static class BasePackageClassesConfig {}

    @Test
    void scansPackagesOfBasePackageClassesAnchors() {
        DefaultListableBeanFactory registry = register(BasePackageClassesConfig.class);

        assertThat(registry.getBeanDefinitionNames()).containsExactly(ScanCrmWebhooks.class.getName());
        assertClientBeanDefinition(registry, ScanCrmWebhooks.class);
    }

    @EnableWebhookClients(
            clients = ExplicitCrmWebhooks.class,
            basePackages = "com.jinternals.webhooks.producer.fixtures.scan")
    static class UnionConfig {}

    @Test
    void takesTheUnionOfSelectorsDeduplicated() {
        DefaultListableBeanFactory registry = register(UnionConfig.class);

        assertThat(registry.getBeanDefinitionNames())
                .containsExactlyInAnyOrder(ExplicitCrmWebhooks.class.getName(), ScanCrmWebhooks.class.getName());
    }

    @EnableWebhookClients(
            clients = ScanCrmWebhooks.class,
            basePackages = "com.jinternals.webhooks.producer.fixtures.scan")
    static class OverlappingConfig {}

    @Test
    void registersEachClientOnlyOnceWhenSelectorsOverlap() {
        DefaultListableBeanFactory registry = register(OverlappingConfig.class);

        assertThat(registry.getBeanDefinitionNames()).containsExactly(ScanCrmWebhooks.class.getName());
    }

    // Placed in the fixtures.scan package so the "scan the annotated class's own package" default finds
    // ScanCrmWebhooks (which lives there) when no selector is set.
    @Test
    void scansTheAnnotatedClassOwnPackageWhenNoSelectorIsSet() {
        DefaultListableBeanFactory registry =
                register(com.jinternals.webhooks.producer.fixtures.scan.NoSelectorConfig.class);

        assertThat(registry.getBeanDefinitionNames()).contains(ScanCrmWebhooks.class.getName());
        assertClientBeanDefinition(registry, ScanCrmWebhooks.class);
    }

    @Test
    void registersNothingWhenTheAnnotationIsAbsent() {
        // No @EnableWebhookClients: metadata.getAnnotationAttributes(...) returns null, so the attribute
        // helpers must treat every selector as empty. The config lives in a package with no @WebhookClient
        // interfaces, so even the own-package scan default registers nothing (and must not NPE).
        DefaultListableBeanFactory registry =
                register(com.jinternals.webhooks.producer.fixtures.none.UnannotatedConfig.class);

        assertThat(registry.getBeanDefinitionNames()).isEmpty();
    }

    @Test
    void resolveBasePackagesReturnsEmptyStringForADefaultPackageClassWithoutCrashing() {
        // Regression guard: ClassUtils.getPackageName("DefaultPkgConfig") returns "" instead of crashing on
        // substring(0, lastIndexOf('.')) == substring(0, -1).
        AnnotationMetadata metadata = org.mockito.Mockito.mock(AnnotationMetadata.class);
        org.mockito.Mockito.when(metadata.getClassName()).thenReturn("DefaultPkgConfig");
        java.util.Map<String, Object> attrs = java.util.Map.of(
                "clients", new Class<?>[0],
                "basePackageClasses", new Class<?>[0],
                "basePackages", new String[0]);

        assertThat(WebhookClientRegistrar.resolveBasePackages(metadata, attrs)).containsExactly("");
    }

    @Test
    void loadClientUsesTheThreadContextClassLoader() throws Exception {
        // Spring Boot DevTools loads user classes in a RestartClassLoader set as the thread context ClassLoader,
        // which is a child of the library's own ClassLoader. Class.forName(name) would only search the library's
        // ClassLoader and miss classes visible solely to the context ClassLoader.
        String dynamicName = "com.jinternals.webhooks.producer.DynamicContextOnlyClient";
        byte[] bytecode = new net.bytebuddy.ByteBuddy()
                .makeInterface()
                .name(dynamicName)
                .make()
                .getBytes();
        ClassLoader contextOnlyLoader = new ClassLoader(ScanCrmWebhooks.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (dynamicName.equals(name)) {
                    return defineClass(name, bytecode, 0, bytecode.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(contextOnlyLoader);
        try {
            Class<?> loaded = WebhookClientRegistrar.loadClient(dynamicName);
            assertThat(loaded.getName()).isEqualTo(dynamicName);
            assertThat(loaded.getClassLoader()).isSameAs(contextOnlyLoader);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    @Test
    void scanIgnoresNonInterfaceCandidates() {
        DefaultListableBeanFactory registry = register(MixedScanConfig.class);

        // The scanned package also holds a @WebhookClient-annotated CLASS, which the scanner must ignore;
        // only the interface is registered.
        assertThat(registry.getBeanDefinitionNames())
                .containsExactly(com.jinternals.webhooks.producer.fixtures.mixed.MixedScanWebhooks.class.getName());
    }

    @EnableWebhookClients(basePackages = "com.jinternals.webhooks.producer.fixtures.mixed")
    static class MixedScanConfig {}

    @EnableWebhookClients(clients = com.jinternals.webhooks.producer.fixtures.defaults.DefaultMethodWebhooks.class)
    static class DefaultMethodConfig {}

    @Test
    void validationSkipsDefaultMethods() {
        // DefaultMethodWebhooks has a default method with an otherwise-invalid signature (non-void, no
        // @WebhookType arg); it must be skipped so the client registers without throwing.
        DefaultListableBeanFactory registry = register(DefaultMethodConfig.class);

        assertThat(registry.getBeanDefinitionNames())
                .containsExactly(
                        com.jinternals.webhooks.producer.fixtures.defaults.DefaultMethodWebhooks.class.getName());
    }

    @WebhookClient(destination = "crm")
    interface InvalidAbstractMethodClient {
        // Abstract (not default/synthetic): missing @WebhookType arg -> validation must reject it eagerly.
        void publish(String notAnEvent);
    }

    @EnableWebhookClients(clients = InvalidAbstractMethodClient.class)
    static class InvalidAbstractMethodConfig {}

    @Test
    void validationRejectsAnInvalidAbstractMethod() {
        // Proves abstract methods ARE validated (the isDefault()/isSynthetic() skip does not swallow them):
        // registration must throw for the missing @WebhookType argument.
        assertThatThrownBy(() -> register(InvalidAbstractMethodConfig.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    // --- Direct validateClientMethods(...) unit tests: unambiguous coverage of the per-method guard. ---

    @Test
    void validateAcceptsAWellFormedClient() {
        // An abstract, void, single-@WebhookType-arg method must pass without throwing.
        WebhookClientRegistrar.validateClientMethods(ScanCrmWebhooks.class);
    }

    @Test
    void validateSkipsDefaultMethodsDirectly() {
        // The default method on this interface has an invalid signature; it must be skipped, not rejected.
        WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.defaults.DefaultMethodWebhooks.class);
    }

    @Test
    void validateRejectsAnInvalidAbstractMethodDirectly() {
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientMethods(InvalidAbstractMethodClient.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    @Test
    void abstractInterfaceMethodIsAWebhookMethod() throws Exception {
        java.lang.reflect.Method contactCreated = ScanCrmWebhooks.class.getMethod(
                "contactCreated", com.jinternals.webhooks.producer.fixtures.ContactCreated.class);

        assertThat(WebhookClientRegistrar.isWebhookMethod(contactCreated)).isTrue();
    }

    @Test
    void defaultInterfaceMethodIsNotAWebhookMethod() throws Exception {
        java.lang.reflect.Method describe =
                com.jinternals.webhooks.producer.fixtures.defaults.DefaultMethodWebhooks.class.getMethod("describe");

        assertThat(WebhookClientRegistrar.isWebhookMethod(describe)).isFalse();
    }

    // A generic base class + a type-narrowing override makes the compiler emit a synthetic bridge method
    // on the subclass. A CLASS bridge is synthetic but NOT default, so it isolates the isSynthetic() guard
    // (coverage tooling such as JaCoCo also injects synthetic, non-default methods onto interfaces).
    static class GenericPublisher<E> {
        void publish(E event) {}
    }

    static class NarrowedPublisher extends GenericPublisher<String> {
        @Override
        void publish(String event) {}
    }

    @Test
    void syntheticNonDefaultBridgeMethodIsNotAWebhookMethod() {
        java.lang.reflect.Method bridge = java.util.Arrays.stream(NarrowedPublisher.class.getDeclaredMethods())
                .filter(java.lang.reflect.Method::isSynthetic)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a synthetic bridge method on NarrowedPublisher"));

        assertThat(bridge.isDefault()).as("a class bridge must not be default").isFalse();
        assertThat(WebhookClientRegistrar.isWebhookMethod(bridge)).isFalse();
    }

    @Test
    void staticInterfaceMethodIsNotAWebhookMethod() throws Exception {
        java.lang.reflect.Method describe =
                com.jinternals.webhooks.producer.fixtures.statics.StaticMethodWebhooks.class.getMethod("describe");

        assertThat(describe.isDefault())
                .as("a static method must not be default")
                .isFalse();
        assertThat(WebhookClientRegistrar.isWebhookMethod(describe)).isFalse();
    }

    @EnableWebhookClients(clients = com.jinternals.webhooks.producer.fixtures.statics.StaticMethodWebhooks.class)
    static class StaticMethodConfig {}

    @Test
    void validationSkipsStaticMethods() {
        // StaticMethodWebhooks has a static method with an otherwise-invalid signature; it must be skipped
        // so the client registers without throwing.
        DefaultListableBeanFactory registry = register(StaticMethodConfig.class);

        assertThat(registry.getBeanDefinitionNames())
                .containsExactly(
                        com.jinternals.webhooks.producer.fixtures.statics.StaticMethodWebhooks.class.getName());
    }

    @Test
    void validateSkipsStaticMethodsDirectly() {
        // The static method on this interface has an invalid signature; it must be skipped, not rejected.
        WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.statics.StaticMethodWebhooks.class);
    }

    // --- Inherited abstract methods are validated (getMethods(), not getDeclaredMethods()), deduplicated. ---

    @Test
    void validateValidatesInheritedAbstractWebhookMethods() {
        // InheritedWebhooks declares NO methods itself; its only webhook method is inherited (abstract) from
        // BaseWebhooks. A well-formed one must pass without throwing, and the one inherited method is counted.
        int validated = WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.inherited.InheritedWebhooks.class);
        assertThat(validated).isEqualTo(1);
    }

    @Test
    void validateValidatesEveryDistinctWebhookMethod() {
        // Two distinct, well-formed webhook methods: BOTH must be validated and counted as two distinct
        // dedup keys. A mutant that collapses signature identity (e.g. always returning an empty list) would
        // treat the second method as a duplicate of the first and count only one — this pins count == 2.
        int validated = WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.multi.TwoMethodWebhooks.class);
        assertThat(validated).isEqualTo(2);
    }

    @Test
    void validateRejectsASecondInvalidMethodNotJustTheFirst() {
        // First method valid, second invalid (no @WebhookType arg). Validation must still reach and reject
        // the second method. A signature-collapse bug would dedup it against the first (distinct) method and
        // skip it, letting the malformed client register — so this must throw for the second method.
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientMethods(
                        com.jinternals.webhooks.producer.fixtures.multi.ValidThenInvalidWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    @Test
    void validateRejectsAnInvalidInheritedAbstractMethod() {
        // Proves getMethods() (not getDeclaredMethods()) is used: the invalid method is only inherited.
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientMethods(
                        com.jinternals.webhooks.producer.fixtures.inherited.InvalidInheritedWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    @Test
    void validateAcceptsAnOverriddenMethod() {
        // OverridingWebhooks re-declares a method it also inherits. Java collapses a straight override to a
        // single getMethods() entry, so this simply confirms the override registers cleanly as one method.
        int validated = WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.inherited.OverridingWebhooks.class);
        assertThat(validated).isEqualTo(1);
    }

    @Test
    void validateDeduplicatesADiamondInheritedMethod() {
        // A diamond: the SAME webhook method signature is inherited from two unrelated super-interfaces, so
        // getMethods() genuinely surfaces it TWICE. Dedup-by-signature must validate it exactly once, so the
        // validation count is 1 (not 2). This is the case where the seen.add() guard is load-bearing: a
        // mutant that drops the guard would validate the duplicate and count 2.
        int validated = WebhookClientRegistrar.validateClientMethods(
                com.jinternals.webhooks.producer.fixtures.diamond.DiamondWebhooks.class);
        assertThat(validated).isEqualTo(1);
    }

    @EnableWebhookClients(clients = com.jinternals.webhooks.producer.fixtures.inherited.InvalidInheritedWebhooks.class)
    static class InvalidInheritedConfig {}

    @Test
    void registrationRejectsAnInvalidInheritedAbstractMethod() {
        assertThatThrownBy(() -> register(InvalidInheritedConfig.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("is not annotated with @WebhookType");
    }

    // --- Type-level validation: the client must be an interface, annotated, with a non-blank destination. ---

    @Test
    void validateClientInterfaceAcceptsAWellFormedClient() {
        WebhookClientRegistrar.validateClientInterface(ScanCrmWebhooks.class);
    }

    @Test
    void validateClientInterfaceRejectsANonInterface() {
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientInterface(
                        com.jinternals.webhooks.producer.fixtures.invalidtype.NotAnInterfaceWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("must be an interface");
    }

    @Test
    void validateClientInterfaceRejectsAMissingAnnotation() {
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientInterface(
                        com.jinternals.webhooks.producer.fixtures.invalidtype.MissingAnnotationWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("is missing the @WebhookClient annotation");
    }

    @Test
    void validateClientInterfaceRejectsABlankDestination() {
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClientInterface(
                        com.jinternals.webhooks.producer.fixtures.invalidtype.BlankDestinationWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("must declare a non-blank destination");
    }

    @Test
    void validateClientRunsBothTypeAndMethodValidation() {
        // The combined entry point accepts a well-formed client (interface + annotation + non-blank
        // destination + valid methods) without throwing.
        WebhookClientRegistrar.validateClient(ScanCrmWebhooks.class);
    }

    @Test
    void validateClientFailsTypeValidationBeforeMethodValidation() {
        // A non-interface fails at the type check; it never reaches method validation.
        assertThatThrownBy(() -> WebhookClientRegistrar.validateClient(
                        com.jinternals.webhooks.producer.fixtures.invalidtype.NotAnInterfaceWebhooks.class))
                .isInstanceOf(InvalidWebhookClientException.class)
                .hasMessageContaining("must be an interface");
    }

    @Test
    void loadClientResolvesAnExistingInterface() {
        assertThat(WebhookClientRegistrar.loadClient(ScanCrmWebhooks.class.getName()))
                .isEqualTo(ScanCrmWebhooks.class);
    }

    @Test
    void loadClientFailsWithAClearMessageForAMissingClass() {
        assertThatThrownBy(() -> WebhookClientRegistrar.loadClient("com.jinternals.webhooks.NoSuchClient"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load @WebhookClient interface")
                .hasMessageContaining("com.jinternals.webhooks.NoSuchClient")
                .hasCauseInstanceOf(ClassNotFoundException.class);
    }
}
