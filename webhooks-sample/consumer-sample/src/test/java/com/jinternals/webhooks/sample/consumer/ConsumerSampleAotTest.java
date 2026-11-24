package com.jinternals.webhooks.sample.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.config.WebhookProperties;
import com.jinternals.webhooks.consumer.dispatch.WebhookDispatcher;
import com.jinternals.webhooks.consumer.web.WebhookEndpointRegistry;
import com.jinternals.webhooks.core.registry.WebhookTypeRegistry;
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

/** Verifies Spring Boot AOT code generation, compilation, and context refresh for {@link ConsumerSampleApplication}. */
@CompileWithForkedClassLoader
class ConsumerSampleAotTest {

    @Test
    @SuppressWarnings("unchecked")
    void aotGenerationCompilesAndRefreshesConsumerEndpointsAndHandlers() {
        GenericWebApplicationContext sourceContext = new GenericWebApplicationContext(new MockServletContext());
        ConfigDataEnvironmentPostProcessor.applyTo(sourceContext.getEnvironment());
        new AnnotatedBeanDefinitionReader(sourceContext).register(ConsumerSampleApplication.class);

        TestGenerationContext generationContext = new TestGenerationContext(ConsumerSampleApplication.class);
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

                assertThat(aotContext.getBean(WebhookEndpointRegistry.class)).isNotNull();
                assertThat(aotContext.getBean(WebhookDispatcher.class)).isNotNull();
                assertThat(aotContext.getBean(WebhookTypeRegistry.class).classOf("contact.created"))
                        .isNotNull();
                assertThat(aotContext
                                .getBean(WebhookProperties.class)
                                .getConsumer()
                                .getEndpoints())
                        .hasSize(3);
            }
        });
    }
}
