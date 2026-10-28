package org.techhouse.cache;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class CacheFitWarningsTest {
    private static final String DB = "fitDb";
    private static final String COLL = "fitColl";
    private static final String PK_WARNING = "The primary-key index of " + DB + "|" + COLL;
    private static final String FIELD_WARNING = "The field index status|String of " + DB + "|" + COLL;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;
    private UserCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        cache = new UserCache();
        final var fs = mock(FileSystem.class);
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 500; i++) {
            rows.add(new PkIndexEntry(DB, COLL, "row-" + i, i, 1, 0));
        }
        when(fs.readWholePkIndexFile(DB, COLL)).thenReturn(rows);
        TestUtils.setPrivateField(cache, "fs", fs);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void loadTwiceUnderLock() throws Exception {
        locks.lock(DB, COLL);
        try {
            cache.getPkIndexAndLoadIfNecessary(DB, COLL);
            cache.getPkIndexAndLoadIfNecessary(DB, COLL);
            final List<FieldIndexEntry<?>> rows = new ArrayList<>();
            for (var i = 0; i < 50; i++) {
                rows.add(new FieldIndexEntry<>(DB, COLL, "value-" + i, new HashSet<>(Set.of("id-" + i))));
            }
            cache.admitMaintainedFieldIndex(DB, COLL, Cache.getIndexIdentifier("status", String.class), rows);
            cache.admitMaintainedFieldIndex(DB, COLL, Cache.getIndexIdentifier("status", String.class), rows);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private static void assertLoggedTimes(MockedStatic<LogWriter> logWriter, String expected, int times) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))), times(times));
    }

    @Test
    public void anOutgrownIndexIsWarnedAboutOnceUntilItsCollectionIsDropped() throws Exception {
        try (var logWriter = mockStatic(LogWriter.class)) {
            loadTwiceUnderLock();
            assertLoggedTimes(logWriter, PK_WARNING, 1);
            assertLoggedTimes(logWriter, FIELD_WARNING, 1);

            cache.evictCollection(DB, COLL);
            loadTwiceUnderLock();
            assertLoggedTimes(logWriter, PK_WARNING, 2);
            assertLoggedTimes(logWriter, FIELD_WARNING, 2);

            cache.evictDatabase(DB);
            loadTwiceUnderLock();
            assertLoggedTimes(logWriter, PK_WARNING, 3);
        }
    }

    @Test
    public void anIndexThatFitsMaxMemoryIsNeverWarnedAbout() {
        final var warnings = new CacheFitWarnings();
        try (var logWriter = mockStatic(LogWriter.class)) {
            warnings.pkIndexOutgrows(DB, COLL, 200L, 200L);
            warnings.fieldIndexOutgrows(DB, COLL, "status|String", 200L, 200L);
            logWriter.verifyNoInteractions();
            warnings.pkIndexOutgrows(DB, COLL, 201L, 200L);
            assertLoggedTimes(logWriter, PK_WARNING, 1);
        }
    }
}
