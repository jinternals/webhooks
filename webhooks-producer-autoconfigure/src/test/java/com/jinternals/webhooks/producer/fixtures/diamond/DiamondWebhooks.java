package com.jinternals.webhooks.producer.fixtures.diamond;

import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A {@code @WebhookClient} that inherits the <em>same</em> webhook method signature from two unrelated
 * super-interfaces (a diamond). Unlike a simple override, {@code getMethods()} surfaces such a method
 * <strong>twice</strong> — once per declaring super-interface — so the registrar's dedup-by-signature is
 * genuinely exercised: the method must be validated exactly once (SPEC.md §19).
 */
@WebhookClient(destination = "crm")
public interface DiamondWebhooks extends LeftWebhooks, RightWebhooks {}
