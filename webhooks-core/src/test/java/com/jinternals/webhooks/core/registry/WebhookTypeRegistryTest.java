package com.jinternals.webhooks.core.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.core.annotations.WebhookType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookTypeRegistryTest {

    @WebhookType("sample.one")
    public static class SampleOne {}

    @Test
    void classOfReturnsRegisteredTypeOrNull() {
        WebhookTypeRegistry registry = new WebhookTypeRegistry(Map.of("sample.one", SampleOne.class));
        assertThat(registry.classOf("sample.one")).isEqualTo(SampleOne.class);
        assertThat(registry.classOf("unknown")).isNull();
    }

    @Test
    void asMapReturnsCopyOfMappings() {
        WebhookTypeRegistry registry = new WebhookTypeRegistry(Map.of("sample.one", SampleOne.class));
        assertThat(registry.asMap()).containsEntry("sample.one", SampleOne.class);
    }
}
