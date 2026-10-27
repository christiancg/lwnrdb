package org.techhouse.unit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.StartupWarnings;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class StartupWarningsCacheFitTest {
    private static final int DOCUMENTS = 100_000;

    private final Configuration config = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(config, "maxMemoryBytes", Long.class);
        final var page = new AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0L);
        page.setEntryCount(DOCUMENTS);
        IocContainer.get(Cache.class).addAdminPageEntries(TestGlobals.DB, TestGlobals.COLL, page);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemory);
        TestUtils.standardTearDown();
    }

    private static void assertLogged(MockedStatic<LogWriter> logWriter, String expected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))));
    }

    private static void assertNoPkIndexWarning(MockedStatic<LogWriter> logWriter) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("The primary-key index of"))),
                never());
    }

    @Test
    public void aCollectionWhosePkIndexCannotFitIsWarnedAbout() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", DOCUMENTS * CacheSizeEstimator.minimumPkEntryBytes() - 1);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCollectionsOutgrowMaxMemory();
            assertLogged(logWriter, "The primary-key index of " + TestGlobals.DB + "|" + TestGlobals.COLL);
            assertLogged(logWriter, "Raise maxMemory above");
        }
    }

    @Test
    public void aCollectionThatFitsIsNotWarnedAbout() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", DOCUMENTS * CacheSizeEstimator.minimumPkEntryBytes());
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCollectionsOutgrowMaxMemory();
            assertNoPkIndexWarning(logWriter);
        }
    }

    @Test
    public void anUnlimitedOrDisabledCacheIsNeverWarnedAbout() throws Exception {
        for (final var budget : new long[]{Globals.CACHE_UNLIMITED, Globals.CACHE_DISABLED}) {
            TestUtils.setPrivateField(config, "maxMemoryBytes", budget);
            try (var logWriter = mockStatic(LogWriter.class)) {
                StartupWarnings.warnIfCollectionsOutgrowMaxMemory();
                logWriter.verifyNoInteractions();
            }
        }
    }
}
