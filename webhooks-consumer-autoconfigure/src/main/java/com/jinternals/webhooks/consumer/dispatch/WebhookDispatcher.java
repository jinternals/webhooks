package com.jinternals.webhooks.consumer.dispatch;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.web.error.NoWebhookHandlerException;
import com.jinternals.webhooks.core.annotations.WebhookType;
import io.micrometer.observation.annotation.Observed;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

/**
 * Scans all beans for {@code @WebhookHandler} methods at startup, keyed by the event parameter's
 * type, then routes each verified + deserialized event (plus {@link WebhookMetadata}) to its handler
 * (see SPEC.md §10).
 *
 * <p>Supported signatures (metadata is recognised by type, not position):
 * <ul>
 *   <li>{@code (EventType event)}</li>
 *   <li>{@code (EventType event, WebhookMetadata metadata)}</li>
 *   <li>{@code (WebhookMetadata metadata, EventType event)}</li>
 * </ul>
 *
 * <p><strong>Discovery scope.</strong> To avoid force-initializing the host's unrelated or lazy beans just to
 * scan them, handlers are discovered from bean <em>types</em> resolved without instantiation. A handler bean
 * must therefore expose a resolvable type that declares the {@code @WebhookHandler} method: a concrete class,
 * a {@code @Bean}-returned instance, or any (JDK- or CGLIB-) proxied bean. The one unsupported case is a
 * handler whose type is knowable only <em>after</em> its {@code FactoryBean} is initialized — such a bean
 * reports a {@code null} type during the scan and is skipped rather than eagerly created. This is a
 * deliberate tradeoff in favour of the no-eager-init guarantee (SPEC.md §10).
 */
public class WebhookDispatcher implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final ApplicationContext applicationContext;
    private final Map<Class<?>, WebhookHandlerMethod> handlers = new HashMap<>();

    public WebhookDispatcher(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        // Resolve bean *types* without instantiating anything (allowEagerInit=false): a drop-in library must
        // not force the host's lazy beans, factory products, or unrelated singletons to initialize early just
        // to be scanned (SPEC.md §10). Only a bean whose type actually declares a @WebhookHandler method is
        // then fetched from the context, so at most the handler-bearing beans are instantiated here.
        String[] names = applicationContext.getBeanNamesForType(Object.class, true, false);
        for (String name : names) {
            Class<?> type = applicationContext.getType(name);
            if (type == null) {
                continue; // type not resolvable without instantiation; skip rather than force creation
            }
            Class<?> candidate = handlerClassFor(name, type);
            if (candidate == null) {
                continue; // not a handler-bearing bean; skip without instantiating it
            }
            Object bean = applicationContext.getBean(name);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            ReflectionUtils.doWithMethods(
                    targetClass,
                    method -> register(bean, method),
                    method -> method.isAnnotationPresent(WebhookHandler.class));
        }
        log.info("Registered {} webhook handler(s): {}", handlers.size(), handlers.values());
    }

    /**
     * Return the class to introspect for {@code @WebhookHandler} methods for the bean {@code name} of
     * resolved {@code type}, or {@code null} if it is plainly not a handler bean (so the caller skips it
     * without instantiating it).
     *
     * <p>For a plain bean this is just {@code getUserClass(type)} — the CGLIB-stripped user class whose own
     * methods carry the annotation. A <strong>JDK dynamic proxy</strong> is the hard case: its type is a
     * {@code $ProxyN} implementing only the bean's interfaces, and method-level annotations on the concrete
     * target are NOT visible on the proxy's methods — so a naive {@code hasHandlerMethod(proxyClass)} check
     * wrongly reports "no handler" and the proxied handler is silently dropped. A JDK proxy singleton is,
     * however, already instantiated (the proxy would not exist otherwise), so fetching it to read its target
     * class costs no eager initialization — the no-eager-init guarantee (SPEC.md §10) is about not forcing
     * <em>uncreated</em> lazy/factory beans, which this is not. We therefore resolve the proxy's target class
     * and introspect that.
     */
    private Class<?> handlerClassFor(String name, Class<?> type) {
        Class<?> introspectable = java.lang.reflect.Proxy.isProxyClass(type)
                ? AopUtils.getTargetClass(applicationContext.getBean(name))
                : org.springframework.util.ClassUtils.getUserClass(type);
        return hasHandlerMethod(introspectable) ? introspectable : null;
    }

    /** True if {@code type} (or a supertype) declares at least one {@code @WebhookHandler} method. */
    private static boolean hasHandlerMethod(Class<?> type) {
        boolean[] found = {false};
        ReflectionUtils.doWithMethods(
                type, method -> found[0] = true, method -> method.isAnnotationPresent(WebhookHandler.class));
        return found[0];
    }

    private void register(Object bean, Method method) {
        // Resolve a method that is actually invocable on the bean *instance* — so it is selected against the
        // bean's own (possibly proxy) class, NOT the target class. This distinction is load-bearing:
        //   - CGLIB proxy: bean.getClass() is a subclass of the target, so the concrete method stays invocable;
        //   - JDK dynamic proxy: bean.getClass() is a $ProxyN implementing the handler's *interface*, so
        //     selectInvocableMethod bridges the concrete target method to the interface method the proxy
        //     actually exposes. Selecting against the target class here would yield a concrete-class Method
        //     that a JDK proxy instance cannot be invoked with ("object is not an instance of declaring
        //     class"), silently breaking handlers on interface-proxied beans (transactions, security,
        //     @Retryable, etc.) (SPEC.md §10).
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        Parameter[] params = method.getParameters();
        if (params.length == 0 || params.length > 2) {
            throw new IllegalStateException(invalid(method)
                    + " must declare one @WebhookType event parameter and an optional WebhookMetadata.");
        }

        Class<?> eventType = null;
        boolean wantsMetadata = false;
        boolean metadataFirst = false;

        for (int i = 0; i < params.length; i++) {
            Class<?> paramType = params[i].getType();
            if (paramType.equals(WebhookMetadata.class)) {
                wantsMetadata = true;
                metadataFirst = (i == 0);
            } else if (paramType.isAnnotationPresent(WebhookType.class)) {
                if (eventType != null) {
                    throw new IllegalStateException(invalid(method) + " has more than one @WebhookType parameter.");
                }
                eventType = paramType;
            } else {
                throw new IllegalStateException(invalid(method) + " has unsupported parameter " + paramType.getName()
                        + " (expected a @WebhookType event or WebhookMetadata).");
            }
        }

        if (eventType == null) {
            throw new IllegalStateException(invalid(method) + " has no @WebhookType event parameter.");
        }

        WebhookHandlerMethod handler =
                new WebhookHandlerMethod(bean, invocable, eventType, wantsMetadata, metadataFirst);
        WebhookHandlerMethod existing = handlers.putIfAbsent(eventType, handler);
        if (existing != null) {
            throw new IllegalStateException("Duplicate @WebhookHandler for event type " + eventType.getName() + ": "
                    + existing + " and " + handler);
        }
    }

    /**
     * Route an event to its handler. Throws {@link NoWebhookHandlerException} when no handler is
     * registered for its type — a modelled, accepted type with no {@code @WebhookHandler} is a config
     * gap, not a success: silently acknowledging it would let the sender stop retrying while nothing
     * processed the event (SPEC.md §10, §15).
     *
     * <p>Instrumented with {@link Observed @Observed} (SPEC.md §13): when the host app has actuator (and
     * optionally a tracer bridge) on the classpath this emits a {@code webhook.receive} timer and a
     * tracing span. Disable via the standard {@code management.observations.enable.webhook.receive=false}.
     */
    @Observed(name = "webhook.receive", contextualName = "webhook receive")
    public void dispatch(Object event, WebhookMetadata metadata) {
        WebhookHandlerMethod handler = handlers.get(event.getClass());
        if (handler == null) {
            throw new NoWebhookHandlerException(
                    metadata.type(), event.getClass().getName());
        }
        handler.invoke(event, metadata);
    }

    private static String invalid(Method method) {
        return "@WebhookHandler " + method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
