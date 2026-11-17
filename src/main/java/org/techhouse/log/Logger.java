package org.techhouse.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;

public class Logger {
    private static final String MESSAGE_SEPARATOR = " - ";
    private static final Character LOG_ENTRY_SEPARATOR = '-';
    private static final String EXCEPTION_MESSAGE_SEPARATOR = " -> ";
    private static final String CLASS_MESSAGE_SEPARATOR = ": ";
    private static final String CAUSED_BY = "Caused by: ";
    private static final String STACK_FRAME_INDENT = "\tat ";
    private static final int DESCRIBED_EXCEPTION_CAPACITY = 1024;
    private final Class<?> tClass;

    public Logger(Class<?> tClass) {
        this.tClass = tClass;
    }

    public boolean isEnabled(LogSeverity severity) {
        return severity.ordinal() <= threshold().ordinal();
    }

    private static LogSeverity threshold() {
        try {
            return LogSeverity.valueOf(Configuration.getInstance().getLogLevel().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return LogSeverity.INFO;
        }
    }

    public void error(Supplier<String> message) {
        lazy(LogSeverity.ERROR, message);
    }

    public void warning(Supplier<String> message) {
        lazy(LogSeverity.WARNING, message);
    }

    public void info(Supplier<String> message) {
        lazy(LogSeverity.INFO, message);
    }

    private void lazy(LogSeverity severity, Supplier<String> message) {
        if (isEnabled(severity)) {
            internalWriteLog(severity, message.get(), null);
        }
    }

    public static Logger logFor(Class<?> tClass) {
        return new Logger(tClass);
    }

    public void fatal(String message) {
        internalWriteLog(LogSeverity.FATAL, message, null);
    }

    public void fatal(String message, Exception exception) {
        internalWriteLog(LogSeverity.FATAL, message, exception);
    }

    public void error(String message) {
        internalWriteLog(LogSeverity.ERROR, message, null);
    }

    public void error(String message, Throwable exception) {
        internalWriteLog(LogSeverity.ERROR, message, exception);
    }

    public void warning(String message) {
        internalWriteLog(LogSeverity.WARNING, message, null);
    }

    public void warning(String message, Throwable exception) {
        internalWriteLog(LogSeverity.WARNING, message, exception);
    }

    public void info(String message) {
        internalWriteLog(LogSeverity.INFO, message, null);
    }

    private void internalWriteLog(LogSeverity severity, String message, Throwable exception) {
        if (!isEnabled(severity)) {
            return;
        }
        String logEntry = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + LOG_ENTRY_SEPARATOR
                + severity.name() + LOG_ENTRY_SEPARATOR + tClass.getName() + MESSAGE_SEPARATOR
                + LogSanitizer.neutralise(message);
        if (exception != null) {
            logEntry += EXCEPTION_MESSAGE_SEPARATOR + describe(exception);
        }
        LogWriter.writeLogEntry(logEntry);
    }

    private static String describe(Throwable exception) {
        final var out = new StringBuilder(DESCRIBED_EXCEPTION_CAPACITY);
        final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var current = exception; current != null && seen.add(current); current = current.getCause()) {
            if (current != exception) {
                out.append(Globals.NEWLINE).append(CAUSED_BY);
            }
            out.append(current.getClass().getName());
            final var message = current.getMessage();
            if (message != null) {
                out.append(CLASS_MESSAGE_SEPARATOR).append(LogSanitizer.neutralise(message));
            }
            final var stackTrace = current.getStackTrace();
            if (stackTrace != null) {
                for (final var frame : stackTrace) {
                    out.append(Globals.NEWLINE).append(STACK_FRAME_INDENT).append(frame);
                }
            }
        }
        return out.toString();
    }
}
