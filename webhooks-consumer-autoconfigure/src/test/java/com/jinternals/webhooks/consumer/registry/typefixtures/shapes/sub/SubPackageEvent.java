package com.jinternals.webhooks.consumer.registry.typefixtures.shapes.sub;

import com.jinternals.webhooks.core.annotations.WebhookType;

/** Fixture in a sub-package of the scanned base package: base packages include their sub-packages. */
@WebhookType("fixture.shapes.sub")
public record SubPackageEvent(String id) {}
