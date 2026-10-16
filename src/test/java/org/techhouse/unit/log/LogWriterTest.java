package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LogWriterTest {
    private int origMaxLogFiles;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        origMaxLogFiles = config.getMaxLogFiles();
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        final var logDir = new File(config.getLogPath());
        if (!logDir.exists()) {
            if (!logDir.mkdir()) {
                fail("Failed creating log directory");
            }
        }
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxLogFiles", origMaxLogFiles);
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
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

    @Test
    public void test_creates_log_directory_and_file_when_not_exists() throws IOException {
        File logDir = new File(TestGlobals.LOG_PATH);
        File expectedLogFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR
                + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION);

        LogWriter.createLogPathAndRemoveOldFiles();

        assertTrue(logDir.exists());
        assertTrue(expectedLogFile.exists());
    }

    @Test
    public void test_handles_failed_log_directory_creation()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        String testLogPath = "/invalid/path/that/cant/be/created";
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "logPath", testLogPath);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream, true, StandardCharsets.UTF_8));

        LogWriter.createLogPathAndRemoveOldFiles();

        assertTrue(outputStream.toString(StandardCharsets.UTF_8).contains("Error creating log directory"));

        System.setOut(System.out);
    }

    @Test
    public void test_deletes_old_files_keeps_recent() throws IOException, NoSuchFieldException, IllegalAccessException {
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

        LogWriter.deleteOldLogFiles(tempDir.toPath());

        assertTrue(currentFile.exists());
        assertTrue(recentFile.exists());
        assertFalse(oldFile.exists());
    }

    @Test
    public void test_no_files_to_delete() throws IOException, NoSuchFieldException, IllegalAccessException {
        File tempDir = new File(TestGlobals.LOG_PATH);
        Configuration config = Configuration.getInstance();
        int maxLogFiles = 3;
        TestUtils.setPrivateField(config, "maxLogFiles", maxLogFiles);

        LocalDate now = LocalDate.now();
        File currentFile = new File(tempDir, now.format(DateTimeFormatter.ISO_DATE) + ".log");
        File recentFile1 = new File(tempDir, now.minusDays(1).format(DateTimeFormatter.ISO_DATE) + ".log");
        File recentFile2 = new File(tempDir, now.minusDays(2).format(DateTimeFormatter.ISO_DATE) + ".log");

        if (!currentFile.createNewFile()) {
            fail("Failed creating current log file");
        }
        if (!recentFile1.createNewFile()) {
            fail("Failed creating recent log file");
        }
        if (!recentFile2.createNewFile()) {
            fail("Failed creating recent log file");
        }

        LogWriter.deleteOldLogFiles(tempDir.toPath());

        assertTrue(currentFile.exists());
        assertTrue(recentFile1.exists());
        assertTrue(recentFile2.exists());
        assertEquals(3, Objects.requireNonNull(tempDir.listFiles()).length);
    }

    @Test
    public void test_unrelated_files_in_the_log_folder_are_not_deleted() throws Exception {
        File tempDir = new File(TestGlobals.LOG_PATH);
        TestUtils.setPrivateField(Configuration.getInstance(), "maxLogFiles", 3);
        final var survivors = new File[]{new File(tempDir, "notes.txt"), new File(tempDir, "server.log.1"),
                new File(tempDir, "data.dat"), new File(tempDir, "abc.log"), new File(tempDir, "2020-13-45.log")};
        for (final var survivor : survivors) {
            assertTrue(survivor.createNewFile());
        }
        final var subfolder = new File(tempDir, "old");
        assertTrue(subfolder.mkdir());
        final var expired = new File(tempDir, "2000-01-01.log");
        assertTrue(expired.createNewFile());

        LogWriter.deleteOldLogFiles(tempDir.toPath());

        for (final var survivor : survivors) {
            assertTrue(survivor.exists(), survivor.getName() + " is not a dated log and must survive");
        }
        assertTrue(subfolder.exists());
        assertFalse(expired.exists());
    }

    @Test
    public void test_logPath_pointing_at_a_populated_folder_keeps_everything_but_expired_logs() throws Exception {
        final var workDir = new File(TestGlobals.LOG_PATH, "work");
        assertTrue(workDir.mkdir());
        final var pom = new File(workDir, "pom.xml");
        Files.writeString(pom.toPath(), "<project/>");
        final var nested = new File(new File(workDir, "src"), "Main.java");
        assertTrue(nested.getParentFile().mkdir());
        Files.writeString(nested.toPath(), "class Main {}");
        final var expired = new File(workDir, "2001-02-03.log");
        assertTrue(expired.createNewFile());
        TestUtils.setPrivateField(Configuration.getInstance(), "logPath", workDir.getPath());

        LogWriter.createLogPathAndRemoveOldFiles();

        assertTrue(pom.exists());
        assertTrue(nested.exists());
        assertFalse(expired.exists());
        assertTrue(new File(workDir, logFileName(LocalDate.now())).exists());
    }

    @Test
    public void test_an_expired_dated_log_that_is_a_directory_is_left_alone() throws Exception {
        File tempDir = new File(TestGlobals.LOG_PATH);
        TestUtils.setPrivateField(Configuration.getInstance(), "maxLogFiles", 3);
        final var folder = new File(tempDir, "2000-01-01.log");
        assertTrue(folder.mkdir());

        LogWriter.deleteOldLogFiles(tempDir.toPath());

        assertTrue(folder.isDirectory());
    }

    @Test
    public void test_a_created_log_folder_is_owner_only() throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        final var logDir = new File(TestGlobals.LOG_PATH);
        TestUtils.deleteFolder(logDir);

        LogWriter.createLogPathAndRemoveOldFiles();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(logDir.toPath())));
    }

    @Test
    public void test_write_log_entry_to_empty_file() throws IOException {
        final var logFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        LogWriter.writeLogEntry("Test log message");
        String fileContent = Files.readString(logFile.toPath());
        assertEquals("Test log message", fileContent);
    }

    @Test
    public void test_write_empty_log_entry() throws IOException {
        final var logFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        LogWriter.writeLogEntry("");
        String fileContent = Files.readString(logFile.toPath());
        assertEquals("", fileContent);
    }

    @Test
    public void test_the_days_file_rolls_once_it_passes_max_log_file_size() throws Exception {
        final var config = Configuration.getInstance();
        final var logFile = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR + logFileName(LocalDate.now()));
        final var rolled = new File(TestGlobals.LOG_PATH + Globals.FILE_SEPARATOR
                + LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE) + ".1.log");
        TestUtils.setPrivateField(config, "maxLogFileSize", "1Kb");
        try {
            LogWriter.flushAndClose();
            for (var i = 0; i < 40; i++) {
                LogWriter.writeLogEntry("entry-" + i + "-" + "x".repeat(80));
            }
            LogWriter.flushAndClose();
            assertTrue(rolled.exists());
            assertTrue(logFile.length() <= 1024 + 120, "current file is " + logFile.length() + " bytes");
            assertTrue(java.nio.file.Files.readString(logFile.toPath()).contains("entry-39"));
        } finally {
            TestUtils.setPrivateField(config, "maxLogFileSize", ConfigKey.MAX_LOG_FILE_SIZE.defaultValue());
            LogWriter.flushAndClose();
            java.nio.file.Files.deleteIfExists(rolled.toPath());
        }
    }

    @Test
    public void test_rolled_files_are_kept_and_expired_with_their_day() throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxLogFiles", 1);
        final var dir = new File(TestGlobals.LOG_PATH);
        final var keptRolled = new File(dir,
                LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ISO_DATE) + ".1.log");
        final var expiredRolled = new File(dir,
                LocalDate.now().minusDays(5).format(java.time.format.DateTimeFormatter.ISO_DATE) + ".1.log");
        assertTrue(keptRolled.createNewFile() || keptRolled.exists());
        assertTrue(expiredRolled.createNewFile() || expiredRolled.exists());
        try {
            LogWriter.deleteOldLogFiles(dir.toPath());
            assertTrue(keptRolled.exists());
            assertFalse(expiredRolled.exists());
        } finally {
            java.nio.file.Files.deleteIfExists(keptRolled.toPath());
            java.nio.file.Files.deleteIfExists(expiredRolled.toPath());
        }
    }
}
