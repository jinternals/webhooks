package com.jinternals.webhooks.producer.fixtures.scan;

import com.jinternals.webhooks.producer.annotations.EnableWebhookClients;

/**
 * {@code @EnableWebhookClients} with no selector set, living in the same package as {@link ScanCrmWebhooks}.
 * Exercises the registrar's "scan the annotated class's own package" convenience default (SPEC.md §19).
 */
@EnableWebhookClients
public class NoSelectorConfig {}
