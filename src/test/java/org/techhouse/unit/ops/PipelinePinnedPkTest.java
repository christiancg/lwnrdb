package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.UserCache;
import org.techhouse.cache.WholeLoads;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.PipelineContext;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PipelinePinnedPkTest {
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 200; i++) {
            final var data = new JsonObject();
            data.addProperty("_id", "doc-" + i);
            entries.add(DbEntry.fromJsonObject(DB, COLL, data));
        }
        IocContainer.get(FileSystem.class).bulkInsertIntoCollection(DB, COLL, entries);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static PipelineContext context() {
        return PipelineContext.of(0L, Long.MAX_VALUE, null);
    }

    private static int pinnedCount(PipelineContext context) throws IOException {
        final var pinned = context.pinnedPkIndex(DB, COLL);
        assertNotNull(pinned);
        return pinned.size();
    }

    private static WholeLoads wholeLoads() throws Exception {
        return TestUtils.getPrivateField(IocContainer.get(UserCache.class), "wholeLoads", WholeLoads.class);
    }

    private void tinyCache() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
    }

    @Test
    void aReaderWithoutTheCollectionLockGetsNoPin() throws Exception {
        try (var context = context()) {
            assertNull(context.pinnedPkIndex(DB, COLL));
        }
    }

    private static List<PkIndexEntry> pinAfterPointScans(PipelineContext context) throws IOException {
        for (var fetch = 0; fetch < PipelineContext.POINT_SCANS_BEFORE_PIN; fetch++) {
            assertNull(context.pinnedPkIndex(DB, COLL), "a refused pk is point-scanned for the first fetches");
        }
        return context.pinnedPkIndex(DB, COLL);
    }

    @Test
    void refusedPkIsNotPinnedAndRowsAreScanned() throws Exception {
        tinyCache();
        final var userCache = IocContainer.get(UserCache.class);
        final var fs = IocContainer.get(FileSystem.class);
        final var spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);
        locks.lockRead(DB, COLL);
        try (var context = context()) {
            final var found = userCache.getEntriesByIds(DB, COLL, Set.of("doc-1", "doc-7"), context::pinnedPkIndex);
            assertEquals(Set.of("doc-1", "doc-7"),
                    found.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, wholeLoads().pinned(), "nothing is pinned for a pk served by point scans");
        } finally {
            locks.releaseRead(DB, COLL);
            TestUtils.setPrivateField(userCache, "fs", fs);
        }
        verify(spied, never()).readWholePkIndexFile(DB, COLL);
        verify(spied, times(1)).pkRowsFor(eq(DB), eq(COLL), anyCollection());
    }

    @Test
    void aLockHolderLoadsOncePerPipeline() throws Exception {
        tinyCache();
        locks.lockRead(DB, COLL);
        try (var context = context()) {
            final var first = pinAfterPointScans(context);
            assertNotNull(first);
            assertEquals(200, first.size());
            assertSame(first, context.pinnedPkIndex(DB, COLL), "a transient list is pinned for the pipeline");
            assertEquals(1, wholeLoads().pinned());
        } finally {
            locks.releaseRead(DB, COLL);
        }
        assertEquals(0, wholeLoads().pinned(), "closing the pipeline gives its pin back");
    }

    @Test
    void aResidentListIsPinnedWithoutAPermit() throws Exception {
        locks.lockRead(DB, COLL);
        try (var context = context()) {
            final var first = context.pinnedPkIndex(DB, COLL);
            assertSame(first, context.pinnedPkIndex(DB, COLL));
            assertEquals(0, wholeLoads().pinned());
        } finally {
            locks.releaseRead(DB, COLL);
        }
    }

    @Test
    void pinsNeverTakeTheLastTransientPermit() throws Exception {
        tinyCache();
        final var permits = wholeLoads().availablePermits();
        final var contexts = new ArrayList<PipelineContext>();
        locks.lockRead(DB, COLL);
        try {
            for (var pipeline = 0; pipeline < permits; pipeline++) {
                final var context = context();
                contexts.add(context);
                pinAfterPointScans(context);
            }
            assertEquals(permits - 1, wholeLoads().pinned());
            assertEquals(1, wholeLoads().availablePermits(), "one permit stays free for loads");
            assertNull(contexts.getLast().pinnedPkIndex(DB, COLL), "a pipeline that found no free pin point-scans");
        } finally {
            contexts.forEach(PipelineContext::close);
            locks.releaseRead(DB, COLL);
        }
        assertEquals(permits, wholeLoads().availablePermits());
    }

    @Test
    void aFailedLoadIsNotPinned() throws Exception {
        tinyCache();
        final var index = new File(TestGlobals.PATH + "/" + DB + "/" + COLL + "/" + COLL + "-pk.idx");
        final var original = Files.readAllBytes(index.toPath());
        Files.writeString(index.toPath(), "garbage\n", StandardCharsets.UTF_8);
        locks.lockRead(DB, COLL);
        try (var context = context()) {
            assertThrows(IOException.class, () -> context.pinnedPkIndex(DB, COLL));
            Files.write(index.toPath(), original);
            assertEquals(200, pinnedCount(context));
        } finally {
            locks.releaseRead(DB, COLL);
        }
        assertEquals(0, wholeLoads().pinned());
    }

    @Test
    void separateCollectionsArePinnedSeparately() throws Exception {
        TestUtils.createTestJoinCollection();
        locks.lockRead(DB, COLL);
        locks.lockRead(DB, TestGlobals.JOIN_COLL);
        try (var context = context()) {
            assertEquals(List.of(), context.pinnedPkIndex(DB, TestGlobals.JOIN_COLL));
            assertEquals(200, pinnedCount(context));
        } finally {
            locks.releaseRead(DB, TestGlobals.JOIN_COLL);
            locks.releaseRead(DB, COLL);
        }
    }
}
