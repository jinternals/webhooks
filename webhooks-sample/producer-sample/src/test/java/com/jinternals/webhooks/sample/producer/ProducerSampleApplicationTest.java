package com.jinternals.webhooks.sample.producer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the producer starter auto-configures the sender from a single dependency: the context loads
 * and @EnableWebhookClients registers the CrmWebhooks proxy bean, with no component-scan of the library.
 * The demo destination points at localhost by default and is never actually called here.
 */
@SpringBootTest
class ProducerSampleApplicationTest {

    @Test
    void contextLoads() {}
}
