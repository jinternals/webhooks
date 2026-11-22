package com.jinternals.webhooks.consumer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinternals.webhooks.consumer.annotations.WebhookHandler;
import com.jinternals.webhooks.consumer.config.WebhookConsumerAutoConfiguration;
import com.jinternals.webhooks.consumer.config.WebhookConsumerWebAutoConfiguration;
import com.jinternals.webhooks.consumer.model.WebhookMetadata;
import com.jinternals.webhooks.consumer.support.Signing;
import com.jinternals.webhooks.core.annotations.WebhookType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end context test over the real auto-configuration: boots the library's web layer (the
 * {@code WebConfiguration} resolver + interceptor and the {@code WebhookEndpointRegistry} mapping
 * registration), signs a genuine Standard Webhooks request, and asserts it routes, verifies,
 * deserializes, and dispatches to a typed handler. This is the coverage source for the Spring-wiring
 * glue that is deliberately excluded from the mutation gate.
 */
@SpringBootTest(
        classes = WebhookConsumerIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class WebhookConsumerIntegrationTest {

    @WebhookType("contact.created")
    public static class ContactCreated {
        public String id;
        public String email;
    }

    static final List<String> DISPATCHED = new ArrayList<>();

    // @AutoConfigurationPackage registers THIS test's package as a Spring Boot auto-configuration package,
    // exactly as @SpringBootApplication would in a real host app. That is what the library's zero-config
    // webhookTypeRegistry bean reads (via AutoConfigurationPackages) to discover the @WebhookType ContactCreated
    // event below — no base-package property.
    @Configuration
    @AutoConfigurationPackage
    @ImportAutoConfiguration({
        WebhookConsumerAutoConfiguration.class,
        WebhookConsumerWebAutoConfiguration.class,
        AopAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class
    })
    static class TestApp {
        @Bean
        TestHandler testHandler() {
            return new TestHandler();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * A real ObservationRegistry wired to the meter registry, standing in for what actuator would
         * provide. @Primary so it wins over the library's ObservationRegistry.NOOP fallback. This lets the
         * test prove the @Observed/ObservedAspect chain on WebhookDispatcher#dispatch actually records.
         */
        @Bean
        @Primary
        ObservationRegistry observationRegistry(MeterRegistry meterRegistry) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig()
                    .observationHandler(new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(
                            meterRegistry));
            return registry;
        }
    }

    static class TestHandler {
        @WebhookHandler
        void onContact(ContactCreated event, WebhookMetadata metadata) {
            DISPATCHED.add(event.id + "/" + metadata.type());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void reset() {
        DISPATCHED.clear();
    }

    @Test
    void routesVerifiesAndDispatchesSignedWebhook() throws Exception {
        String id = "msg_integration_1";
        String ts = Signing.now();
        String body = "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-99\",\"email\":\"a@b.c\"}}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        mockMvc.perform(post("/webhooks/crm")
                        .header("webhook-id", id)
                        .header("webhook-timestamp", ts)
                        .header("webhook-signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(DISPATCHED).containsExactly("c-99/contact.created");

        // The @Observed dispatch recorded a webhook.receive timer through the ObservedAspect →
        // ObservationRegistry → DefaultMeterObservationHandler chain (SPEC §13). The registry is shared
        // across the cached context, so assert at-least-once rather than an exact (order-dependent) count.
        assertThat(meterRegistry.find("webhook.receive").timer()).isNotNull();
        assertThat(meterRegistry.find("webhook.receive").timer().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void routesToAllTypesEndpoint() throws Exception {
        String id = "msg_integration_all";
        String ts = Signing.now();
        String body = "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-all\",\"email\":\"x@y.z\"}}";
        String sig = Signing.sign(Signing.SECRET, id, ts, body);

        mockMvc.perform(post("/webhooks/all")
                        .header("webhook-id", id)
                        .header("webhook-timestamp", ts)
                        .header("webhook-signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(DISPATCHED).containsExactly("c-all/contact.created");
    }

    @Test
    void rejectsTamperedSignatureWith400() throws Exception {
        String id = "msg_integration_2";
        String ts = Signing.now();
        String body = "{\"type\":\"contact.created\",\"data\":{\"id\":\"c-1\"}}";
        String sig = Signing.sign(Signing.SECRET, id, ts, "{\"type\":\"other\"}"); // wrong body

        mockMvc.perform(post("/webhooks/crm")
                        .header("webhook-id", id)
                        .header("webhook-timestamp", ts)
                        .header("webhook-signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(DISPATCHED).isEmpty();
    }
}
