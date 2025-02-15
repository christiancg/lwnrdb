package org.techhouse.unit.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class LogWriterTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        final var logDir = new File(config.getLogPath());
        if (!logDir.exists()) {
            if (!logDir.mkdir()) {
                fail("Failed creating log directory");
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
            TestUtils.deleteFolder(logDir);
        }
    }

    private String logFileName(LocalDate date) {
        return date.format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION;
    }

    // Create log directory and file when they don't exist
    @Test
    public void test_creates_log_directory_and_file_when_not_exists() throws IOException {
        // Arrange

        File logDir = new File(TestGlobals.LOG_PATH);
        File expectedLogFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR +
            LocalDate.now().format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION);

        // Act
        LogWriter.createLogPathAndRemoveOldFiles();

        // Assert
        assertTrue(logDir.exists());
        assertTrue(expectedLogFile.exists());
    }

    // Handle case when log directory creation fails
    @Test
    public void test_handles_failed_log_directory_creation() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        String testLogPath = "/invalid/path/that/cant/be/created";
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "logPath", testLogPath);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream));

        // Act
        LogWriter.createLogPathAndRemoveOldFiles();

        // Assert
        assertTrue(outputStream.toString().contains("Error creating log directory"));

        // Restore system output
        System.setOut(System.out);
    }

    // Deletes files older than maxLogFiles days while keeping current and recent log files
    @Test
    public void test_deletes_old_files_keeps_recent() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        File tempDir = new File(TestGlobals.LOG_PATH);
        Configuration config = Configuration.getInstance();
        int maxLogFiles = 3;
        TestUtils.setPrivateField(config, "maxLogFiles", maxLogFiles);

        LocalDate now = LocalDate.now();
        File currentFile = new File(tempDir, now.format(DateTimeFormatter.ISO_DATE) + ".log");
        File recentFile = new File(tempDir, now.minusDays(1).format(DateTimeFormatter.ISO_DATE) + ".log");
        File oldFile = new File(tempDir, now.minusDays(5).format(DateTimeFormatter.ISO_DATE) + ".log");

        if (!currentFile.createNewFile()) {
            fail("Failed creating current log file");
        }
        if (!recentFile.createNewFile()) {
            fail("Failed creating recent log file");
        }
        if (!oldFile.createNewFile()) {
            fail("Failed creating old log file");
        }

        // Act
        LogWriter.deleteOldLogFiles(tempDir);

        // Assert
        assertTrue(currentFile.exists());
        assertTrue(recentFile.exists());
        assertFalse(oldFile.exists());
    }

    // Directory contains no files to delete (all files are within retention period)
    @Test
    public void test_no_files_to_delete() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        File tempDir = new File(TestGlobals.LOG_PATH);
        Configuration config = Configuration.getInstance();
        int maxLogFiles = 3;
        TestUtils.setPrivateField(config, "maxLogFiles", maxLogFiles);

        LocalDate now = LocalDate.now();
        File currentFile = new File(tempDir, now.format(DateTimeFormatter.ISO_DATE) + ".log");
        File recentFile1 = new File(tempDir, now.minusDays(1).format(DateTimeFormatter.ISO_DATE) + ".log");
        File recentFile2 = new File(tempDir, now.minusDays(2).format(DateTimeFormatter.ISO_DATE) + ".log");

        if(!currentFile.createNewFile()) {
            fail("Failed creating current log file");
        }
        if(!recentFile1.createNewFile()) {
            fail("Failed creating recent log file");
        }
        if(!recentFile2.createNewFile()) {
            fail("Failed creating recent log file");
        }

        // Act
        LogWriter.deleteOldLogFiles(tempDir);

        // Assert
        assertTrue(currentFile.exists());
        assertTrue(recentFile1.exists());
        assertTrue(recentFile2.exists());
        assertEquals(3, Objects.requireNonNull(tempDir.listFiles()).length);
    }

    // Successfully writes log entry to empty file
    @Test
    public void test_write_log_entry_to_empty_file() throws IOException {
        // Arrange
        final var logFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        // Act
        LogWriter.writeLogEntry("Test log message");
        // Assert
        String fileContent = Files.readString(logFile.toPath());
        assertEquals("Test log message", fileContent);
    }

    // Writing empty log entry string
    @Test
    public void test_write_empty_log_entry() throws IOException {
        // Arrange
        final var logFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        // Act
        LogWriter.writeLogEntry("");
        // Assert
        String fileContent = Files.readString(logFile.toPath());
        assertEquals("", fileContent);
    }
}