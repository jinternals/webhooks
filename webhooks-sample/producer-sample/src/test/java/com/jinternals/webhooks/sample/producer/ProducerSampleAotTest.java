package com.jinternals.webhooks.sample.producer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.producer.client.WebhookPublisher;
import com.jinternals.webhooks.producer.config.WebhookProducerProperties;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooks;
import com.jinternals.webhooks.sample.producer.client.CrmWebhooksV2a;
import org.junit.jupiter.api.Test;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

/** Verifies Spring Boot AOT code generation, compilation, and context refresh for {@link ProducerSampleApplication}. */
@CompileWithForkedClassLoader
class ProducerSampleAotTest {

    @Test
    @SuppressWarnings("unchecked")
    void aotGenerationCompilesAndRefreshesWebhookClientProxies() {
        GenericWebApplicationContext sourceContext = new GenericWebApplicationContext(new MockServletContext());
        ConfigDataEnvironmentPostProcessor.applyTo(sourceContext.getEnvironment());
        new AnnotatedBeanDefinitionReader(sourceContext).register(ProducerSampleApplication.class);

        TestGenerationContext generationContext = new TestGenerationContext(ProducerSampleApplication.class);
        ClassName initializerName =
                new ApplicationContextAotGenerator().processAheadOfTime(sourceContext, generationContext);
        generationContext.writeGeneratedContent();

        TestCompiler.forSystem().with(generationContext).compile(compiled -> {
            try (GenericWebApplicationContext aotContext = new GenericWebApplicationContext(new MockServletContext())) {
                aotContext.setClassLoader(compiled.getClassLoader());
                ConfigDataEnvironmentPostProcessor.applyTo(aotContext.getEnvironment());
                ApplicationContextInitializer<GenericApplicationContext> initializer =
                        compiled.getInstance(ApplicationContextInitializer.class, initializerName.canonicalName());
                initializer.initialize(aotContext);
                aotContext.refresh();

                assertThat(aotContext.getBean(CrmWebhooks.class)).isNotNull();
                assertThat(aotContext.getBean(CrmWebhooksV2a.class)).isNotNull();
                assertThat(aotContext.getBean(WebhookPublisher.class)).isNotNull();
                assertThat(aotContext.getBean(WebhookProducerProperties.class).getDestinations())
                        .containsKeys("demo", "demo-v2a");
            }
        });
    }
}
