package com.jinternals.webhooks.sample.consumer.nats;

import com.jinternals.webhooks.consumer.dispatch.HandlerFailureListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingHandlerFailureListener implements HandlerFailureListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingHandlerFailureListener.class);

    private final List<String> failedTypes = new CopyOnWriteArrayList<>();

    @Override
    public void onHandlerFailed(String type, Throwable cause) {
        log.error("Inbound webhook moved to DLQ for type={}: {}", type, cause.getMessage(), cause);
        failedTypes.add(type);
    }

    @Override
    public void onHandlerTimeout(String type) {
        log.error("Inbound webhook timed out for type={}", type);
        failedTypes.add(type);
    }

    public List<String> failedTypes() {
        return List.copyOf(failedTypes);
    }

    public void clear() {
        failedTypes.clear();
    }
}
