/**
 * <strong>Internal framework plumbing</strong> for the inbound webhook receive pipeline. Types in this
 * package are composed internally by {@code WebhookConsumerWebAutoConfiguration} and are not public
 * extension points; prefer the stable SPIs in {@code consumer.verify}, {@code consumer.idempotency},
 * {@code consumer.dispatch}, {@code consumer.metadata}, and {@code consumer.metrics}.
 */
@NullMarked
package com.jinternals.webhooks.consumer.web.receive;

import org.jspecify.annotations.NullMarked;
