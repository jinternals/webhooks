package com.jinternals.webhooks.consumer.registry.typefixtures.shapes;

import com.jinternals.webhooks.core.annotations.WebhookType;

/** Fixture: an abstract class cannot be instantiated as a payload, so the scanner skips it. */
@WebhookType("fixture.shapes.abstract")
public abstract class AbstractEvent {}
