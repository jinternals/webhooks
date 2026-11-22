package com.jinternals.webhooks.consumer.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Attaches a Logback {@link ListAppender} to a class's logger so a test can assert on the
 * <em>formatted</em> log message (and mutation-test that the right branch logged). Captures at
 * {@code DEBUG} so both {@code warn}/{@code debug} lines are observed.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    public static LogCapture forClass(Class<?> type) {
        return new LogCapture(type);
    }

    /** The single captured log line, fully formatted (placeholders substituted). */
    public String lastFormattedMessage() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    /** The formatted message of the first captured line (useful for lines emitted early in a flow). */
    public String firstFormattedMessage() {
        return appender.list.get(0).getFormattedMessage();
    }

    /** Every captured line, fully formatted, in emission order. */
    public java.util.List<String> formattedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public int count() {
        return appender.list.size();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
