package org.techhouse.unit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.StartupWarnings;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.HeapShares;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class StartupWarningsCacheFitTest {
    private static final int DOCUMENTS = 100_000;

    private final Configuration config = Configuration.getInstance();
    private Long savedMaxMemory;
    private Long savedMetadataCacheMaxBytes;
    private Boolean savedScriptsEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(config, "maxMemoryBytes", Long.class);
        savedMetadataCacheMaxBytes = TestUtils.getPrivateField(config, "metadataCacheMaxBytes", Long.class);
        savedScriptsEnabled = TestUtils.getPrivateField(config, "scriptsEnabled", Boolean.class);
        final var page = new AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0L);
        page.setEntryCount(DOCUMENTS);
        IocContainer.get(Cache.class).addAdminPageEntries(TestGlobals.DB, TestGlobals.COLL, page);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", savedMaxMemory);
        TestUtils.setPrivateField(config, "metadataCacheMaxBytes", savedMetadataCacheMaxBytes);
        TestUtils.setPrivateField(config, "scriptsEnabled", savedScriptsEnabled);
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

    @Test
    public void clampIsLoggedOnce() throws Exception {
        final var aboveTheCeiling = HeapShares.maxMemoryCeiling() + 1;
        TestUtils.setPrivateField(config, "maxMemoryBytes", aboveTheCeiling);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfMaxMemoryClamped();
            logWriter.verify(
                    () -> LogWriter.writeLogEntry(argThat(entry -> entry
                            .contains("maxMemory=" + aboveTheCeiling + " is more than 60% of the JVM heap"))),
                    times(1));
            assertLogged(logWriter, "the cache is capped at " + HeapShares.maxMemoryCeiling() + " bytes");
        }
    }

    @Test
    public void aMaxMemoryWithinTheCeilingOrAutoIsNotClamped() throws Exception {
        for (final var configured : new String[]{"auto", String.valueOf(HeapShares.maxMemoryCeiling()), "0", "-1"}) {
            TestUtils.setPrivateField(config, "maxMemoryBytes", configured);
            try (var logWriter = mockStatic(LogWriter.class)) {
                StartupWarnings.warnIfMaxMemoryClamped();
                logWriter.verifyNoInteractions();
            }
        }
    }

    @Test
    public void cachesExceedHeapCountsOneScriptBudget() throws Exception {
        final var xmx = Runtime.getRuntime().maxMemory();
        final var scriptBudget = config.getScriptMaxMemoryBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", Globals.CACHE_DISABLED);
        TestUtils.setPrivateField(config, "scriptsEnabled", true);
        TestUtils.setPrivateField(config, "metadataCacheMaxBytes", xmx - scriptBudget);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCachesExceedHeap();
            logWriter.verifyNoInteractions();
        }
        TestUtils.setPrivateField(config, "metadataCacheMaxBytes", xmx - scriptBudget + 1);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCachesExceedHeap();
            assertLogged(logWriter, "one script budget " + scriptBudget);
        }
    }
}
