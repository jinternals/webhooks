package com.jinternals.webhooks.producer.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * The {@link InvocationHandler} behind a {@code @WebhookClient} proxy (see SPEC.md §19). Each interface
 * method is expected to take a single {@code @WebhookType}-annotated event argument; invoking it publishes that
 * event to the client's configured destination via {@link WebhookPublisher}.
 *
 * <p>Each client method's signature (exactly one {@code @WebhookType} event parameter, {@code void} return) is
 * validated eagerly at startup by {@code WebhookClientRegistrar}, so this handler can publish directly
 * without re-checking. {@code Object} methods ({@code equals}/{@code hashCode}/{@code toString}) are
 * handled locally so the proxy behaves sensibly as a bean, and {@code default} interface methods run their
 * own body (via {@link InvocationHandler#invokeDefault}) instead of being mis-published.
 */
public class WebhookClientInvocationHandler implements InvocationHandler {

    private final String destination;
    private final WebhookPublisher publisher;

    public WebhookClientInvocationHandler(String destination, WebhookPublisher publisher) {
        this.destination = destination;
        this.publisher = publisher;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            // A default helper method on the interface runs its own body, not a publish.
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        // Signature (one @WebhookType event arg, void return) was validated at startup by WebhookClientRegistrar.
        publisher.publish(destination, args[0]);
        return null;
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "WebhookClient[destination=" + destination + "]";
            default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
        };
    }
}
