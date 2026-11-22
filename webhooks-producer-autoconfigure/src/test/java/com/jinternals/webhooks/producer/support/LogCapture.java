package com.jinternals.webhooks.producer.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Attaches a Logback {@link ListAppender} to a class's logger so a test can assert on the
 * <em>formatted</em> log message (and mutation-test that the right branch logged the right text).
 * Captures at {@code DEBUG} so {@code info}/{@code warn}/{@code error} lines are all observed.
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

    /** The last captured log line, fully formatted (placeholders substituted). */
    public String lastFormattedMessage() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    /** Every captured log line, fully formatted, in order. */
    public List<String> formattedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    public int count() {
        return appender.list.size();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
