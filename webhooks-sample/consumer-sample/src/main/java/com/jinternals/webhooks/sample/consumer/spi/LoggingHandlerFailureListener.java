package com.jinternals.webhooks.sample.consumer.spi;

import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sample {@link HandlerFailureListener} SPI implementation (see {@code docs/reference/beans-and-spis.md}).
 *
 * <p>Replaces the default {@code NoOpHandlerFailureListener} automatically via {@code @ConditionalOnMissingBean}.
 * In {@code async} mode ({@code webhooks.consumer.handler.mode=async}), the controller has already returned
 * {@code 204} before the handler executes, so any handler exception or timeout is routed here for alerting or
 * dead-letter persistence.
 */
@Component
public class LoggingHandlerFailureListener implements HandlerFailureListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingHandlerFailureListener.class);

    public record RecordedFailure(String type, String kind, String detail) {}

    private final List<RecordedFailure> failures = new CopyOnWriteArrayList<>();

    @Override
    public void onHandlerFailed(String type, Throwable cause) {
        log.error("Async webhook handler failed for type={}: {}", type, cause.getMessage(), cause);
        failures.add(new RecordedFailure(type, "failed", cause.getMessage()));
    }

    @Override
    public void onHandlerTimeout(String type) {
        log.error("Async webhook handler timed out for type={}", type);
        failures.add(new RecordedFailure(type, "timeout", null));
    }

    public List<RecordedFailure> recordedFailures() {
        return List.copyOf(failures);
    }

    public void clear() {
        failures.clear();
    }
}
