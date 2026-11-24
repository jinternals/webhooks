package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingHandlerFailureListener implements HandlerFailureListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingHandlerFailureListener.class);
    private static final int MAX_RETAINED_FAILURES = 1_000;

    private final ConcurrentLinkedDeque<String> failedTypes = new ConcurrentLinkedDeque<>();
    private final AtomicInteger retainedCount = new AtomicInteger();

    @Override
    public void onHandlerFailed(String type, Throwable cause) {
        log.error("Inbound webhook moved to DLQ for type={}: {}", type, cause.getMessage(), cause);
        record(type);
    }

    @Override
    public void onHandlerTimeout(String type) {
        log.error("Inbound webhook timed out for type={}", type);
        record(type);
    }

    private void record(String type) {
        failedTypes.addLast(type);
        if (retainedCount.incrementAndGet() > MAX_RETAINED_FAILURES && failedTypes.pollFirst() != null) {
            retainedCount.decrementAndGet();
        }
    }

    public List<String> failedTypes() {
        return List.copyOf(failedTypes);
    }

    public void clear() {
        failedTypes.clear();
        retainedCount.set(0);
    }
}
