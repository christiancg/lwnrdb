package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.LogSeverity;
import org.techhouse.log.LogWriter;
import org.techhouse.log.Logger;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LogSeverityThresholdTest {
    private final Logger logger = Logger.logFor(LogSeverityThresholdTest.class);

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        final var logDir = new File(config.getLogPath());
        if (!logDir.exists() && !logDir.mkdir()) {
            fail("Failed creating log directory");
        }
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "logLevel", "INFO");
        LogWriter.flushAndClose();
        final var logFile = currentLogFile();
        if (logFile.exists() && !logFile.delete()) {
            fail("Failed deleting log file");
        }
        final var logDir = new File(Configuration.getInstance().getLogPath());
        if (logDir.exists() && !logDir.delete()) {
            fail("Failed deleting log directory");
        }
    }

    private static File currentLogFile() {
        return new File(Configuration.getInstance().getLogPath() + Globals.FILE_SEPARATOR
                + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION);
    }

    private static String logContents() throws IOException {
        LogWriter.flushAndClose();
        final var file = currentLogFile();
        return file.exists() ? Files.readString(file.toPath()) : "";
    }

    private void setLevel(String level) throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "logLevel", level);
    }

    @Test
    public void test_the_default_level_writes_every_severity() throws Exception {
        setLevel("INFO");
        logger.info("an info line");
        logger.warning("a warning line");
        logger.error("an error line");

        final var contents = logContents();
        assertTrue(contents.contains("an info line"));
        assertTrue(contents.contains("a warning line"));
        assertTrue(contents.contains("an error line"));
    }

    @Test
    public void test_a_warning_threshold_drops_info() throws Exception {
        setLevel("WARNING");
        logger.info("dropped info");
        logger.warning("kept warning");

        final var contents = logContents();
        assertFalse(contents.contains("dropped info"), "INFO must be dropped below the threshold");
        assertTrue(contents.contains("kept warning"));
    }

    @Test
    public void test_an_error_threshold_drops_warnings() throws Exception {
        setLevel("ERROR");
        logger.warning("dropped warning");
        logger.error("kept error");

        final var contents = logContents();
        assertFalse(contents.contains("dropped warning"));
        assertTrue(contents.contains("kept error"));
    }

    @Test
    public void test_a_fatal_threshold_keeps_only_fatal() throws Exception {
        setLevel("FATAL");
        logger.error("dropped error");
        logger.fatal("kept fatal");

        final var contents = logContents();
        assertFalse(contents.contains("dropped error"));
        assertTrue(contents.contains("kept fatal"));
    }

    @Test
    public void test_isEnabled_reflects_the_threshold() throws Exception {
        setLevel("ERROR");
        assertTrue(logger.isEnabled(LogSeverity.FATAL));
        assertTrue(logger.isEnabled(LogSeverity.ERROR));
        assertFalse(logger.isEnabled(LogSeverity.WARNING));
        assertFalse(logger.isEnabled(LogSeverity.INFO));
    }

    @Test
    public void test_a_suppressed_supplier_detail_is_never_evaluated() throws Exception {
        setLevel("ERROR");
        final var evaluated = new boolean[1];

        logger.warning(() -> {
            evaluated[0] = true;
            return "expensive";
        });

        assertFalse(evaluated[0], "the detail supplier must not run below the threshold");
    }

    @Test
    public void test_an_enabled_supplier_detail_is_appended() throws Exception {
        setLevel("INFO");

        logger.info(() -> "prefix detail");

        assertTrue(logContents().contains("prefix detail"));
    }

    @Test
    public void test_consecutive_entries_are_newline_separated() throws Exception {
        setLevel("INFO");
        logger.info("first");
        logger.info("second");

        final var lines = logContents().split(Globals.NEWLINE);
        assertEquals(2, lines.length, "each entry must be on its own line");
        assertTrue(lines[0].contains("first"));
        assertTrue(lines[1].contains("second"));
    }

    @Test
    public void test_an_invalid_level_falls_back_to_writing_everything() throws Exception {
        setLevel("not-a-level");
        logger.info("still written");

        assertTrue(logContents().contains("still written"));
    }
}
