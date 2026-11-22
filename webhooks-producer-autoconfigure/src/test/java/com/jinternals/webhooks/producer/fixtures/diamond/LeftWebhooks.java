package com.jinternals.webhooks.producer.fixtures.diamond;

import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/** One arm of the {@link DiamondWebhooks} diamond: declares the shared webhook method signature. */
public interface LeftWebhooks {
    void contactCreated(ContactCreated event);
}
