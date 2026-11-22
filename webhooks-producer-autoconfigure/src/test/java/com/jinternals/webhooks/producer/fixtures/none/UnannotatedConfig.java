package com.jinternals.webhooks.producer.fixtures.none;

/**
 * A plain class with no {@code @EnableWebhookClients}, in a package that holds no {@code @WebhookClient}
 * interfaces. Drives the registrar's "annotation attributes are null" guard: with no annotation the
 * attribute helpers return empty and the own-package scan default finds nothing, so no beans register.
 */
public class UnannotatedConfig {}
