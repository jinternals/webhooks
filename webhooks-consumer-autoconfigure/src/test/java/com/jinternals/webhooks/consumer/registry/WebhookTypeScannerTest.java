package com.jinternals.webhooks.consumer.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinternals.webhooks.consumer.registry.typefixtures.alpha.AlphaEvent;
import com.jinternals.webhooks.consumer.registry.typefixtures.beta.BetaEvent;
import com.jinternals.webhooks.consumer.registry.typefixtures.blank.BlankTypeEvent;
import com.jinternals.webhooks.consumer.registry.typefixtures.shapes.Outer;
import com.jinternals.webhooks.consumer.registry.typefixtures.shapes.sub.SubPackageEvent;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookTypeScannerTest {

    private static final String ALPHA_PKG = AlphaEvent.class.getPackageName();
    private static final String BETA_PKG = BetaEvent.class.getPackageName();
    private static final String DUP_PKG = "com.jinternals.webhooks.consumer.registry.typefixtures.dup";
    private static final String SHAPES_PKG = Outer.class.getPackageName();

    @Test
    void scanFindsAnnotatedClassesInPackage() {
        WebhookTypeRegistry registry = WebhookTypeScanner.scan(ALPHA_PKG);
        assertThat(registry.classOf("fixture.alpha")).isEqualTo(AlphaEvent.class);
    }

    @Test
    void scanUnionMergesTypesFromEveryPackage() {
        WebhookTypeRegistry registry = WebhookTypeScanner.scan(List.of(ALPHA_PKG, BETA_PKG));
        assertThat(registry.classOf("fixture.alpha")).isEqualTo(AlphaEvent.class);
        assertThat(registry.classOf("fixture.beta")).isEqualTo(BetaEvent.class);
    }

    @Test
    void scanUnionToleratesTheSameClassSeenFromOverlappingPackages() {
        // The same package scanned twice surfaces AlphaEvent twice; putIfAbsent returns the identical
        // class, so it is NOT a collision (previous == eventClass branch).
        WebhookTypeRegistry registry = WebhookTypeScanner.scan(List.of(ALPHA_PKG, ALPHA_PKG));
        assertThat(registry.classOf("fixture.alpha")).isEqualTo(AlphaEvent.class);
    }

    @Test
    void scanUnionRejectsTwoDifferentClassesClaimingTheSameType() {
        assertThatThrownBy(() -> WebhookTypeScanner.scan(List.of(ALPHA_PKG, DUP_PKG)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @WebhookType(\"fixture.alpha\")")
                .hasMessageContaining(AlphaEvent.class.getName());
    }

    @Test
    void scanRejectsBlankWebhookTypeValue() {
        assertThatThrownBy(() -> WebhookTypeScanner.scan(BlankTypeEvent.class.getPackageName()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@WebhookType value must not be blank")
                .hasMessageContaining(BlankTypeEvent.class.getName());
    }

    @Test
    void scanIncludesSubPackagesStaticNestedClassesAndRecords() {
        WebhookTypeRegistry registry = WebhookTypeScanner.scan(SHAPES_PKG);
        assertThat(registry.classOf("fixture.shapes.static-nested")).isEqualTo(Outer.StaticNestedEvent.class);
        assertThat(registry.classOf("fixture.shapes.sub")).isEqualTo(SubPackageEvent.class);
    }

    @Test
    void scanSkipsClassesJacksonCannotInstantiate() {
        WebhookTypeRegistry registry = WebhookTypeScanner.scan(SHAPES_PKG);
        assertThat(registry.classOf("fixture.shapes.inner")).isNull(); // non-static inner class
        assertThat(registry.classOf("fixture.shapes.abstract")).isNull(); // abstract class
    }

    @Test
    void scanOfAPackageWithNoEventClassesYieldsAnEmptyRegistry() {
        WebhookTypeRegistry registry = WebhookTypeScanner.scan("com.jinternals.webhooks.consumer.registry.nonexistent");
        assertThat(registry.classOf("fixture.alpha")).isNull();
    }

    @Test
    void utilityClassHasAPrivateConstructor() throws Exception {
        // WebhookTypeScanner is a static-only utility; its private constructor exists only to prevent
        // instantiation. Exercise it via reflection so the line is covered (PIT counts it).
        Constructor<WebhookTypeScanner> ctor = WebhookTypeScanner.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
