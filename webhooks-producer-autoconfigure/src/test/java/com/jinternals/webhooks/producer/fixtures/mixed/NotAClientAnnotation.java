package com.jinternals.webhooks.producer.fixtures.mixed;

import com.jinternals.webhooks.producer.annotations.WebhookClient;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Fixture annotation meta-annotated with {@link WebhookClient}: {@code @interface} reports
 * {@code isInterface() == true} in both JVM reflection and Spring's ASM metadata, so both the classpath
 * scanner and {@code validateClientInterface} must explicitly exclude annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@WebhookClient(destination = "crm")
public @interface NotAClientAnnotation {}
