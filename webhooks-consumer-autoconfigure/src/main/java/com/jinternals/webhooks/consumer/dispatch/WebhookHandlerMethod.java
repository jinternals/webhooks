package com.jinternals.webhooks.consumer.dispatch;

import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import java.lang.reflect.Method;
import org.springframework.util.ReflectionUtils;

/**
 * A single {@code @WebhookHandler} method bound to its bean, plus the resolved argument layout so it
 * can be invoked with the event (and optionally the {@link WebhookMetadata}) in the right order
 * (see SPEC.md §10).
 */
public final class WebhookHandlerMethod {

    private final Object bean;
    private final Method method;
    private final Class<?> eventType;
    private final boolean wantsMetadata;
    private final boolean metadataFirst;

    public WebhookHandlerMethod(
            Object bean, Method method, Class<?> eventType, boolean wantsMetadata, boolean metadataFirst) {
        this.bean = bean;
        this.method = method;
        this.eventType = eventType;
        this.wantsMetadata = wantsMetadata;
        this.metadataFirst = metadataFirst;
        ReflectionUtils.makeAccessible(method);
    }

    public Class<?> eventType() {
        return eventType;
    }

    /** Invoke the handler, supplying metadata only if the method declares it. */
    public void invoke(Object event, WebhookMetadata metadata) {
        Object[] args;
        if (!wantsMetadata) {
            args = new Object[] {event};
        } else if (metadataFirst) {
            args = new Object[] {metadata, event};
        } else {
            args = new Object[] {event, metadata};
        }
        ReflectionUtils.invokeMethod(method, bean, args);
    }

    @Override
    public String toString() {
        return bean.getClass().getSimpleName() + "#" + method.getName() + "(" + eventType.getSimpleName()
                + (wantsMetadata ? " + WebhookMetadata" : "") + ")";
    }
}
