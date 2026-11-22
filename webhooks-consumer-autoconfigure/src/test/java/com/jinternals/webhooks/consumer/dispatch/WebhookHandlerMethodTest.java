package com.jinternals.webhooks.consumer.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.core.annotations.WebhookType;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

class WebhookHandlerMethodTest {

    @WebhookType("x")
    static class EventX {}

    static class Target {
        Object[] captured;

        void eventOnly(EventX e) {
            captured = new Object[] {e};
        }

        void eventThenMeta(EventX e, WebhookMetadata m) {
            captured = new Object[] {e, m};
        }

        void metaThenEvent(WebhookMetadata m, EventX e) {
            captured = new Object[] {m, e};
        }
    }

    private Method method(String name, Class<?>... params) {
        return ReflectionUtils.findMethod(Target.class, name, params);
    }

    private WebhookMetadata meta() {
        return new WebhookMetadata("x", "id", null, null, "/p", "ip", new Object(), Map.of(), Map.of());
    }

    @Test
    void invokesEventOnly() {
        Target target = new Target();
        WebhookHandlerMethod handler =
                new WebhookHandlerMethod(target, method("eventOnly", EventX.class), EventX.class, false, false);
        EventX event = new EventX();
        handler.invoke(event, meta());
        assertThat(target.captured).containsExactly(event);
        assertThat(handler.eventType()).isEqualTo(EventX.class);
    }

    @Test
    void invokesEventThenMetadata() {
        Target target = new Target();
        WebhookHandlerMethod handler = new WebhookHandlerMethod(
                target, method("eventThenMeta", EventX.class, WebhookMetadata.class), EventX.class, true, false);
        EventX event = new EventX();
        WebhookMetadata m = meta();
        handler.invoke(event, m);
        assertThat(target.captured).containsExactly(event, m);
    }

    @Test
    void invokesMetadataThenEvent() {
        Target target = new Target();
        WebhookHandlerMethod handler = new WebhookHandlerMethod(
                target, method("metaThenEvent", WebhookMetadata.class, EventX.class), EventX.class, true, true);
        EventX event = new EventX();
        WebhookMetadata m = meta();
        handler.invoke(event, m);
        assertThat(target.captured).containsExactly(m, event);
    }

    @Test
    void toStringDescribesSignature() {
        WebhookHandlerMethod noMeta =
                new WebhookHandlerMethod(new Target(), method("eventOnly", EventX.class), EventX.class, false, false);
        WebhookHandlerMethod withMeta = new WebhookHandlerMethod(
                new Target(), method("eventThenMeta", EventX.class, WebhookMetadata.class), EventX.class, true, false);

        assertThat(noMeta.toString()).isEqualTo("Target#eventOnly(EventX)");
        assertThat(withMeta.toString()).isEqualTo("Target#eventThenMeta(EventX + WebhookMetadata)");
    }
}
