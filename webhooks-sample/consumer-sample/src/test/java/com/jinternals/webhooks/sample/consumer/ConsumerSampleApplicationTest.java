package com.jinternals.webhooks.sample.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the consumer starter auto-configures the full listener from a single dependency: the context
 * loads with the two configured endpoints, the @WebhookType events are scanned, the @WebhookHandler beans are
 * bound, and the payload validator + metadata contributor wire up — all with no component-scan of the
 * library.
 */
@SpringBootTest
class ConsumerSampleApplicationTest {

    @Test
    void contextLoads() {}
}
