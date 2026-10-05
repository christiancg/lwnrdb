package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.StartupWarnings;
import org.techhouse.config.Globals;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class StartupWarningsCompactionTest {
    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void aMarkerStartupCouldNotRecoverIsNamed() throws Exception {
        final var marker = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.compacting");
        Files.writeString(marker.toPath(), "left behind");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCompactionsLeftUnrecovered();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(marker.getName()))));
        }
        assertTrue(marker.delete());
    }

    @Test
    public void noMarkerSaysNothing() {
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCompactionsLeftUnrecovered();
            logWriter.verifyNoInteractions();
        }
    }
}
