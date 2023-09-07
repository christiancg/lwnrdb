package org.techhouse.log;

import org.techhouse.config.Globals;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

public class Logger {
    private static final String MESSAGE_SEPARATOR = " - ";
    private static final Character LOG_ENTRY_SEPARATOR = '-';
    private static final String EXCEPTION_MESSAGE_SEPARATOR = " -> ";
    private final Class<?> tClass;

    public Logger(Class<?> tClass) {
        this.tClass = tClass;
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

    public void error(String message, Exception exception) {
        internalWriteLog(LogSeverity.ERROR, message, exception);
    }

    public void warning(String message) {
        internalWriteLog(LogSeverity.WARNING, message, null);
    }

    public void warning(String message, Exception exception) {
        internalWriteLog(LogSeverity.WARNING, message, exception);
    }

    public void info(String message) {
        internalWriteLog(LogSeverity.INFO, message, null);
    }

    private void internalWriteLog(LogSeverity severity, String message, Exception exception) {
        String logEntry = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + LOG_ENTRY_SEPARATOR + severity.name() +
                LOG_ENTRY_SEPARATOR + tClass.getName() + MESSAGE_SEPARATOR + message;
        if (exception != null) {
            final var exceptionMessage = exception.getMessage();
            if (exceptionMessage != null) {
                logEntry += EXCEPTION_MESSAGE_SEPARATOR + exceptionMessage;
            }
            final var stackTrace = exception.getStackTrace();
            if (stackTrace != null) {
                logEntry += Arrays.stream(stackTrace).map(StackTraceElement::toString).collect(Collectors.joining(Globals.NEWLINE));
            }
        }
        LogWriter.writeLogEntry(logEntry);
    }
}
