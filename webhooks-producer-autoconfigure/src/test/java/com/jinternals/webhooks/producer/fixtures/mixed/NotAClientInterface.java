package com.jinternals.webhooks.producer.fixtures.mixed;

import com.jinternals.webhooks.producer.annotations.WebhookClient;

/**
 * A concrete class carrying {@code @WebhookClient} in a scanned package. The registrar's scanner only
 * treats interfaces as candidates (SPEC.md §19), so this class must be ignored — asserted by
 * {@code WebhookClientRegistrarTest.scanIgnoresNonInterfaceCandidates}.
 */
@WebhookClient(destination = "crm")
public class NotAClientInterface {}
