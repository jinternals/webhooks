package com.jinternals.webhooks.producer.fixtures.diamond;

import com.jinternals.webhooks.producer.fixtures.ContactCreated;

/** The other arm of the {@link DiamondWebhooks} diamond: declares the same webhook method signature. */
public interface RightWebhooks {
    void contactCreated(ContactCreated event);
}
