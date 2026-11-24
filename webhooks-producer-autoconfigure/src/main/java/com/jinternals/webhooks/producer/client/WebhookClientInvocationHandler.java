package com.jinternals.webhooks.producer.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * The {@link InvocationHandler} behind a {@code @WebhookClient} proxy (see SPEC.md §19). Each interface
 * method is expected to take a single {@code @WebhookType}-annotated event argument; invoking it publishes that
 * event to the client's configured destination via {@link WebhookPublisher}.
 *
 * <p>Each client method's signature (exactly one {@code @WebhookType} event parameter, {@code void} return) is
 * validated eagerly at startup by {@code WebhookClientRegistrar}, so this handler can publish directly
 * without re-checking. {@code Object} methods ({@code equals}/{@code hashCode}/{@code toString}) are
 * handled locally so the proxy behaves sensibly as a bean, and {@code default} interface methods run their
 * own body (via cached {@link MethodHandle}s from {@link MethodHandles#privateLookupIn}) instead of being
 * mis-published.
 */
public class WebhookClientInvocationHandler implements InvocationHandler {

    private final String destination;
    private final WebhookPublisher publisher;
    private final ConcurrentMap<Method, MethodHandle> defaultMethods = new ConcurrentHashMap<>();

    public WebhookClientInvocationHandler(String destination, WebhookPublisher publisher) {
        this.destination = destination;
        this.publisher = publisher;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            // A default helper method on the interface runs its own body, not a publish. privateLookupIn
            // supports package-private @WebhookClient interfaces in the host app's package (where
            // InvocationHandler.invokeDefault would fail with IllegalAccessException).
            return defaultMethods
                    .computeIfAbsent(method, WebhookClientInvocationHandler::unreflectDefault)
                    .bindTo(proxy)
                    .invokeWithArguments(args);
        }
        // Signature (one @WebhookType event arg, void return) was validated at startup by WebhookClientRegistrar.
        publisher.publish(destination, Objects.requireNonNull(args)[0]);
        return null;
    }

    static MethodHandle unreflectDefault(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        try {
            return MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup())
                    .unreflectSpecial(method, declaringClass);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access default method " + method, e);
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, @Nullable Object @Nullable [] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == Objects.requireNonNull(args)[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "WebhookClient[destination=" + destination + "]";
            default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
        };
    }
}
