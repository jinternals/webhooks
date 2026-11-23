package com.jinternals.webhooks.core.redact;

import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.annotations.Redact;
import java.lang.reflect.Modifier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import org.junit.jupiter.api.Test;

class WebhookRedactorTest {

    static class Event {
        String id = "c-1";

        @Redact
        String email = "john@example.com";

        @Redact(show = 4)
        String card = "4242424242421234";

        @Redact(show = 4)
        String shortPin = "99"; // shorter than show -> fully redacted

        @Redact
        String nullSecret = null;

        static String ignoredStatic = "static";
    }

    static class Base {
        @Redact
        String inherited = "secret";
    }

    static class Child extends Base {
        String visible = "shown";
    }

    @Test
    void masksAnnotatedFieldsAndRendersOthers() {
        String out = WebhookRedactor.toRedactedString(new Event());
        assertThat(out).startsWith("Event{");
        assertThat(out).contains("id=c-1");
        assertThat(out).contains("email=***REDACTED***");
        assertThat(out).contains("card=************1234"); // last 4 kept
        assertThat(out).contains("shortPin=***REDACTED***"); // value <= show
        assertThat(out).contains("nullSecret=null"); // null redacted field
        assertThat(out).doesNotContain("static"); // static skipped
    }

    @Test
    void nullEventRendersNull() {
        assertThat(WebhookRedactor.toRedactedString(null)).isEqualTo("null");
    }

    @Test
    void utilityClassIsNotInstantiable() throws Exception {
        // WebhookRedactor is a static-only utility; its private constructor exists solely to prevent
        // instantiation. Invoking it via reflection documents that intent and covers the line.
        var ctor = WebhookRedactor.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isInstanceOf(WebhookRedactor.class);
    }

    @Test
    void includesInheritedRedactedFields() {
        String out = WebhookRedactor.toRedactedString(new Child());
        assertThat(out).contains("visible=shown");
        assertThat(out).contains("inherited=***REDACTED***");
    }

    @Test
    void maskHandlesNullAndShortAndPartial() {
        assertThat(WebhookRedactor.mask(null, 4)).isEqualTo("null");
        assertThat(WebhookRedactor.mask("ab", 4)).isEqualTo("***REDACTED***");
        assertThat(WebhookRedactor.mask("abcdef", 0)).isEqualTo("***REDACTED***");
        assertThat(WebhookRedactor.mask("abcdef", 2)).isEqualTo("****ef");
        assertThat(WebhookRedactor.mask("abcdef", -1)).isEqualTo("***REDACTED***"); // negative clamped to 0
    }

    @Test
    void maskBoundaryAtExactLength() {
        // length == show -> fully redacted (the <= boundary); length == show+1 -> keep the tail.
        assertThat(WebhookRedactor.mask("abcd", 4)).isEqualTo("***REDACTED***"); // 4 <= 4
        assertThat(WebhookRedactor.mask("abcde", 4)).isEqualTo("*bcde"); // 5 > 4 -> partial
    }

    @Test
    void hasRedactedFieldsReflectsAnnotations() {
        assertThat(WebhookRedactor.hasRedactedFields(Event.class)).isTrue();
        assertThat(WebhookRedactor.hasRedactedFields(NoSecrets.class)).isFalse();
    }

    @Test
    void skipsSyntheticFields() throws Exception {
        // Synthetic fields (e.g. JaCoCo's $jacocoData, Kotlin/Lombok plumbing) must be skipped. We
        // synthesise a class with a genuine synthetic field at runtime to exercise that branch
        // deterministically, independent of the compiler's handling of inner classes.
        Class<?> type = new ByteBuddy()
                .subclass(Object.class)
                .name("com.jinternals.webhooks.core.redact.WithSynthetic")
                .defineField("real", String.class, Visibility.PUBLIC)
                .defineField("ghost", String.class, Visibility.PUBLIC, SyntheticState.SYNTHETIC)
                .make()
                .load(getClass().getClassLoader(), INJECTION)
                .getLoaded();

        Object instance = type.getDeclaredConstructor().newInstance();
        type.getField("real").set(instance, "kept");

        String out = WebhookRedactor.toRedactedString(instance);
        assertThat(out).contains("real=kept");
        assertThat(out).doesNotContain("ghost"); // synthetic field skipped
    }

    @Test
    void repeatedCallsReuseCachedPlan() {
        String first = WebhookRedactor.toRedactedString(new Event());
        String second = WebhookRedactor.toRedactedString(new Event());
        assertThat(second).isEqualTo(first);
    }

    static class NoSecrets {
        String a = "x";
    }
}
