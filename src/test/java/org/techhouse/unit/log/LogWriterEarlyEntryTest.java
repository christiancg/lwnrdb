package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LogWriterEarlyEntryTest {
    private final Configuration config = Configuration.getInstance();
    private final PrintStream stdout = System.out;

    @BeforeEach
    public void setUp() throws Exception {
        LogWriter.flushAndClose();
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        assertTrue(new File(TestGlobals.LOG_PATH).isDirectory() || new File(TestGlobals.LOG_PATH).mkdirs());
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.setOut(stdout);
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        LogWriter.flushAndClose();
        TestUtils.deleteFolder(new File(TestGlobals.LOG_PATH));
    }

    private static File todaysLog() {
        return new File(TestGlobals.LOG_PATH,
                LocalDate.now().format(DateTimeFormatter.ISO_DATE) + Globals.LOG_FILE_EXTENSION);
    }

    @Test
    public void anEntryLoggedBeforeTheLogPathIsKnownIsPrintedAndWrittenLater() throws Exception {
        final var captured = new ByteArrayOutputStream();
        TestUtils.setPrivateField(config, "logPath", null);
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

        LogWriter.writeLogEntry("entry from the configuration load");

        System.setOut(stdout);
        final var printed = captured.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("entry from the configuration load"), printed);
        assertFalse(printed.contains("could not write log entry"), printed);

        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        LogWriter.writeLogEntry("entry after the load");

        final var written = Files.readString(todaysLog().toPath());
        final var early = written.indexOf("entry from the configuration load");
        assertTrue(early >= 0, written);
        assertTrue(early < written.indexOf("entry after the load"), written);
    }
}
