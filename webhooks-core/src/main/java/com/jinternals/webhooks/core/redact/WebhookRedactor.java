package com.jinternals.webhooks.core.redact;

import com.jinternals.webhooks.core.annotations.Redact;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.ReflectionUtils;

/**
 * Renders an event object to a log-safe string, masking any field annotated with {@link Redact}
 * (see SPEC.md §9a).
 *
 * <p><strong>Performance:</strong> all reflection is done <em>once per class</em> and cached as a
 * precompiled {@link FieldPlan} array. Every call after the first for a given type does zero
 * annotation lookups and zero field discovery — it just iterates the cached plan and reads field
 * values through already-{@code setAccessible} {@link Field} handles. The cache is a
 * {@link ConcurrentHashMap}, so lookups are lock-free on the hot path.
 *
 * <p>This is the <strong>only</strong> framework-sanctioned way to log an event. Calling a getter
 * for a {@code @Redact} field and logging it directly bypasses redaction — that is on you.
 */
public final class WebhookRedactor {

    public static final String REDACTED = "***REDACTED***";

    /** Cached, precompiled per-class render plan. Lock-free reads; computed once per type. */
    private static final ConcurrentHashMap<Class<?>, FieldPlan[]> CACHE = new ConcurrentHashMap<>();

    private WebhookRedactor() {}

    /** One field's cached render instructions: the accessible handle + whether/how to mask. */
    private record FieldPlan(Field field, String name, boolean redact, int show) {}

    /**
     * A log-safe representation of {@code event}: {@code ClassName{field=value, secret=***REDACTED***}}.
     * Non-annotated fields are rendered with their {@code toString()}; {@code @Redact} fields are
     * masked. Returns {@code "null"} for a null event.
     */
    public static String toRedactedString(Object event) {
        if (event == null) {
            return "null";
        }
        Class<?> type = event.getClass();
        FieldPlan[] plan = CACHE.computeIfAbsent(type, WebhookRedactor::compile);

        StringBuilder sb = new StringBuilder(type.getSimpleName()).append('{');
        for (int i = 0; i < plan.length; i++) {
            FieldPlan fp = plan[i];
            Object raw = ReflectionUtils.getField(fp.field(), event);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fp.name()).append('=').append(fp.redact() ? mask(raw, fp.show()) : String.valueOf(raw));
        }
        return sb.append('}').toString();
    }

    /** Mask a single value: full {@code ***REDACTED***}, or keep {@code show} trailing chars. */
    public static String mask(Object value, int show) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value);
        int keep = Math.max(0, show);
        if (keep == 0 || s.length() <= keep) {
            return REDACTED;
        }
        int maskedLen = s.length() - keep;
        return "*".repeat(maskedLen) + s.substring(maskedLen);
    }

    /** True if the class declares at least one {@code @Redact} field (cached). */
    public static boolean hasRedactedFields(Class<?> type) {
        FieldPlan[] plan = CACHE.computeIfAbsent(type, WebhookRedactor::compile);
        for (FieldPlan fp : plan) {
            if (fp.redact()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reflect the whole class hierarchy once, resolve {@code @Redact} per field, pre-call
     * {@code setAccessible}, and freeze the result into an array ordered declaration-first
     * (super-class fields last). Only invoked on the first render of each type.
     */
    private static FieldPlan[] compile(Class<?> type) {
        List<FieldPlan> plans = new ArrayList<>();
        // Walks up to (but excluding) Object, whose getSuperclass() is null — so the loop always
        // terminates at the Object.class guard and a null class is never observed.
        for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                ReflectionUtils.makeAccessible(field);
                Redact redact = field.getAnnotation(Redact.class);
                plans.add(new FieldPlan(field, field.getName(), redact != null, redact != null ? redact.show() : 0));
            }
        }
        return plans.toArray(new FieldPlan[0]);
    }
}
