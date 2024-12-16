package org.techhouse.unit.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.LogSeverity;
import org.techhouse.log.LogWriter;
import org.techhouse.log.Logger;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

public class LoggerTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        final var logDir = new File(config.getLogPath());
        if (logDir.exists()) {
            if (!logDir.delete()) {
                fail("Failed deleting log directory");
            }
        }
    }

    @AfterEach
    public void tearDown() {
        Configuration config = Configuration.getInstance();
        final var logfile = new File(config.getLogPath() + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        if (logfile.exists()) {
            if (!logfile.delete()) {
                fail("Failed deleting log file");
            }
        }
        final var logDir = new File(config.getLogPath());
        if (logDir.exists()) {
            if (!logDir.delete()) {
                fail("Failed deleting log directory");
            }
        }
    }

    private String logFileName(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION;
    }

    // Constructor successfully initializes Logger instance with valid Class parameter
    @Test
    public void test_constructor_initializes_with_valid_class() throws IllegalAccessException {
        Logger logger = new Logger(String.class);
        assertNotNull(logger);
        final var fieldOptional = Arrays.stream(logger.getClass().getDeclaredFields()).filter(f -> f.getName().equals("tClass")).findFirst();
        if (fieldOptional.isPresent()) {
            final var field = fieldOptional.get();
            field.setAccessible(true);
            assertEquals(String.class, field.get(logger));
        } else {
            fail("Field 'tClass' not found");
        }
    }

    // Constructor handles null Class parameter
    @Test
    public void test_constructor_handles_null_class() throws IllegalAccessException {
        Logger logger = new Logger(null);
        assertNotNull(logger);
        final var fieldOptional = Arrays.stream(logger.getClass().getDeclaredFields()).filter(f -> f.getName().equals("tClass")).findFirst();
        if (fieldOptional.isPresent()) {
            final var field = fieldOptional.get();
            field.setAccessible(true);
            assertNull(field.get(logger));
        } else {
            fail("Field 'tClass' not found");
        }
    }

    // Returns a new Logger instance when called with a valid Class parameter
    @Test
    public void test_logfor_returns_logger_with_valid_class() throws IllegalAccessException {
        Logger logger = Logger.logFor(String.class);
        assertNotNull(logger);
        final var fieldOptional = Arrays.stream(logger.getClass().getDeclaredFields()).filter(f -> f.getName().equals("tClass")).findFirst();
        if (fieldOptional.isPresent()) {
            final var field = fieldOptional.get();
            field.setAccessible(true);
            assertEquals(String.class, field.get(logger));
        } else {
            fail("Field 'tClass' not found");
        }
    }

    public static class FatalTest { }

    // Logs fatal message with correct severity level FATAL
    @Test
    public void test_fatal_logs_with_fatal_severity() {
        Logger logger = Logger.logFor(FatalTest.class);

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.fatal("Test fatal message");

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-FATAL-") &&
                                    logEntry.contains("Test fatal message")
                    ))
            );
        }
    }

    // Handles empty message string
    @Test
    public void test_fatal_handles_empty_message() {
        Logger logger = Logger.logFor(FatalTest.class);

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.fatal("*");

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-FATAL-") &&
                                    logEntry.endsWith("*")
                    ))
            );
        }
    }

    // Logs fatal message with exception using correct severity level FATAL
    @Test
    public void test_fatal_logs_message_with_exception() {
        Logger logger = Logger.logFor(FatalTest.class);
        Exception testException = new RuntimeException("Test exception");
        String testMessage = "Fatal error occurred";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.fatal(testMessage, testException);

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-FATAL-") &&
                                    logEntry.contains("Test exception")
                    ))
            );
        }
    }

    public static class ErrorTest { }

    // Verify error message is logged with ERROR severity level
    @Test
    public void test_error_logs_message_with_error_severity() {
        // Arrange
        Logger logger = Logger.logFor(ErrorTest.class);
        String testMessage = "Test error message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            // Act
            logger.error(testMessage);

            // Assert
            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains(LogSeverity.ERROR.name()) &&
                                    logEntry.contains(testMessage)
                    ))
            );
        }
    }

    // Test with empty message string
    @Test
    public void test_error_logs_empty_message() {
        // Arrange
        Logger logger = Logger.logFor(ErrorTest.class);
        String emptyMessage = "";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            // Act
            logger.error(emptyMessage);

            // Assert
            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains(LogSeverity.ERROR.name()) &&
                                    logEntry.contains(ErrorTest.class.getName())
                    ))
            );
        }
    }

    // Logs error message with exception details in correct format
    @Test
    public void test_fatal_logs_message_with_exception_details() {
        Logger logger = Logger.logFor(ErrorTest.class);
        Exception testException = new RuntimeException("Test exception");
        String expectedMessage = "Test error message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.fatal(expectedMessage, testException);

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-FATAL-") &&
                                    logEntry.contains("Test exception")
                    ))
            );
        }
    }

    // Logs error message with exception details in correct format
    @Test
    public void test_error_logs_message_with_exception_details() {
        Logger logger = Logger.logFor(ErrorTest.class);
        Exception testException = new RuntimeException("Test exception");
        String expectedMessage = "Test error message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.error(expectedMessage, testException);

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("ERROR") &&
                                    logEntry.contains(ErrorTest.class.getName()) &&
                                    logEntry.contains(expectedMessage) &&
                                    logEntry.contains("Test exception")
                    ))
            );
        }
    }

    // Handle null exception parameter
    @Test
    public void test_error_logs_message_with_null_exception() {
        Logger logger = Logger.logFor(ErrorTest.class);
        String expectedMessage = "Test error message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.error(expectedMessage, null);

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("ERROR") &&
                                    logEntry.contains(ErrorTest.class.getName()) &&
                                    logEntry.contains(expectedMessage) &&
                                    !logEntry.contains("->")
                    ))
            );
        }
    }

    public static class WarningTest { }

    // Verify warning message is logged with WARNING severity level
    @Test
    public void test_warning_logs_message_with_warning_severity() {
        // Arrange
        Logger logger = Logger.logFor(WarningTest.class);
        String testMessage = "Test warning message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            // Act
            logger.warning(testMessage);

            // Assert
            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains(LogSeverity.WARNING.name()) &&
                                    logEntry.contains(testMessage) &&
                                    logEntry.contains(WarningTest.class.getName())
                    ))
            );
        }
    }

    // Test with empty message string
    @Test
    public void test_warning_logs_empty_message() {
        // Arrange
        Logger logger = Logger.logFor(WarningTest.class);
        String emptyMessage = "";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            // Act
            logger.warning(emptyMessage);

            // Assert
            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains(LogSeverity.WARNING.name()) &&
                                    logEntry.contains(WarningTest.class.getName())
                    ))
            );
        }
    }

    // Warning log entry is created with message and exception
    @Test
    public void test_warning_with_message_and_exception() {
        Logger logger = Logger.logFor(WarningTest.class);
        Exception testException = new RuntimeException("Test exception");
        String testMessage = "Test warning message";

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            // Act
            logger.warning(testMessage, testException);

            // Assert
            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains(LogSeverity.WARNING.name()) &&
                                    logEntry.contains(WarningTest.class.getName())
                    ))
            );
        }
    }

    public static class InfoTest { }

    // Verify info message is logged with INFO severity level
    @Test
    public void test_info_message_logged_with_info_severity() {
        Logger logger = Logger.logFor(InfoTest.class);

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.info("Test message");

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-INFO-") &&
                                    logEntry.contains("Test message")
                    ))
            );
        }
    }

    // Pass empty string as message
    @Test
    public void test_info_empty_message() {
        Logger logger = Logger.logFor(InfoTest.class);

        try (MockedStatic<LogWriter> logWriterMock = mockStatic(LogWriter.class)) {
            logger.info("");

            logWriterMock.verify(() ->
                    LogWriter.writeLogEntry(argThat(logEntry ->
                            logEntry.contains("-INFO-") &&
                                    logEntry.endsWith(InfoTest.class.getName() + " - ")
                    ))
            );
        }
    }
}