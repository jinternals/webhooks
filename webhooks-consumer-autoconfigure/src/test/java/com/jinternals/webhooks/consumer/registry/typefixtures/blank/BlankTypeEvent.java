package com.jinternals.webhooks.consumer.registry.typefixtures.blank;

import com.jinternals.webhooks.core.annotations.WebhookType;

@WebhookType("   ")
public record BlankTypeEvent(String id) {}
