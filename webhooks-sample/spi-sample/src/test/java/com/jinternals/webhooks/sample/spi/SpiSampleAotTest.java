package com.jinternals.webhooks.sample.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinternals.webhooks.consumer.idempotency.IdempotencyGuard;
import com.jinternals.webhooks.core.model.Envelope;
import com.jinternals.webhooks.producer.security.DestinationUrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
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

/**
 * Verifies Spring Boot AOT code generation, compilation, dual-starter context refresh, and GraalVM
 * {@code RuntimeHints} registration for {@link SpiSampleApplication}.
 */
@CompileWithForkedClassLoader
class SpiSampleAotTest {

    @Test
    @SuppressWarnings("unchecked")
    void aotGenerationCompilesRefreshesDualStartersAndRegistersNativeHints() {
        GenericWebApplicationContext sourceContext = new GenericWebApplicationContext(new MockServletContext());
        ConfigDataEnvironmentPostProcessor.applyTo(sourceContext.getEnvironment());
        new AnnotatedBeanDefinitionReader(sourceContext).register(SpiSampleApplication.class);

        TestGenerationContext generationContext = new TestGenerationContext(SpiSampleApplication.class);
        ClassName initializerName =
                new ApplicationContextAotGenerator().processAheadOfTime(sourceContext, generationContext);
        generationContext.writeGeneratedContent();

        assertThat(RuntimeHintsPredicates.proxies().forInterfaces(SpiSampleApplication.CrmWebhooks.class))
                .accepts(generationContext.getRuntimeHints());
        assertThat(RuntimeHintsPredicates.reflection().onType(Envelope.class))
                .accepts(generationContext.getRuntimeHints());
        assertThat(RuntimeHintsPredicates.reflection().onType(SpiSampleApplication.ContactCreated.class))
                .accepts(generationContext.getRuntimeHints());
        assertThat(RuntimeHintsPredicates.reflection().onType(SpiSampleApplication.ContactHandlers.class))
                .accepts(generationContext.getRuntimeHints());

        TestCompiler.forSystem().with(generationContext).compile(compiled -> {
            try (GenericWebApplicationContext aotContext = new GenericWebApplicationContext(new MockServletContext())) {
                aotContext.setClassLoader(compiled.getClassLoader());
                ConfigDataEnvironmentPostProcessor.applyTo(aotContext.getEnvironment());
                ApplicationContextInitializer<GenericApplicationContext> initializer =
                        compiled.getInstance(ApplicationContextInitializer.class, initializerName.canonicalName());
                initializer.initialize(aotContext);
                aotContext.refresh();

                assertThat(aotContext.getBean(SpiSampleApplication.CrmWebhooks.class))
                        .isNotNull();
                assertThat(aotContext.getBean(IdempotencyGuard.class)).isInstanceOf(SharedStoreIdempotencyGuard.class);
                assertThat(aotContext.getBean(DestinationUrlValidator.class))
                        .isInstanceOf(PrivateIpBlockingUrlValidator.class);
                assertThat(aotContext.getBean(SpiSampleApplication.RecordedHooks.class))
                        .isNotNull();
            }
        });
    }
}
