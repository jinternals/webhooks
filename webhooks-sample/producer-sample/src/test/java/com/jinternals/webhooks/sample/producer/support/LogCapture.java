package com.jinternals.webhooks.sample.producer.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Attaches a Logback {@link ListAppender} to a class's logger so a test can assert on the
 * <em>formatted</em> log message.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    public static LogCapture forClass(Class<?> type) {
        return new LogCapture(type);
    }

    /** The single captured log line, fully formatted (placeholders substituted). */
    public String lastFormattedMessage() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    public int count() {
        return appender.list.size();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
