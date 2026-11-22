package com.jinternals.webhooks.consumer.registry.typefixtures.dup;

import com.jinternals.webhooks.core.annotations.WebhookType;

/**
 * Fixture that deliberately reuses the "fixture.alpha" type string already claimed by
 * {@code typefixtures.alpha.AlphaEvent}, so a union scan of both packages collides (duplicate-type guard).
 */
@WebhookType("fixture.alpha")
public class DuplicateAlphaEvent {}
