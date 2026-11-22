package com.jinternals.webhooks.consumer.registry.typefixtures.shapes;

import com.jinternals.webhooks.core.annotations.WebhookType;

/**
 * Fixture holder for nested-class scan tests: a {@code static} nested event is discoverable (Jackson can
 * instantiate it), a non-static inner class is not (it needs an enclosing instance).
 */
public class Outer {

    @WebhookType("fixture.shapes.static-nested")
    public static class StaticNestedEvent {}

    @WebhookType("fixture.shapes.inner")
    public class InnerEvent {}
}
