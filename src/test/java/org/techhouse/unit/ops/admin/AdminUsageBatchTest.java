package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.UsageProfileFlushEvent;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UsageCounter;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminUsageBatchTest {
    private static final String USAGE_LOG = Globals.ADMIN_COLLECTION_USAGE_NAME + Globals.WAL_EXTENSION;

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(memoryManagement));
        memoryManagement.takeDirtyUsage();
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        recorder.close();
        memoryManagement.takeDirtyUsage();
        TestUtils.resetUsageCounters(TestUtils.usageTrackerOf(memoryManagement));
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UsageCounter touched(AccessKind kind, String indexKey) {
        memoryManagement.recordAccess(kind, TestGlobals.DB, TestGlobals.COLL, indexKey);
        return memoryManagement.getCounter(kind, TestGlobals.DB, TestGlobals.COLL, indexKey);
    }

    private long usageLogForces() {
        return recorder.forcedNames().stream().filter(USAGE_LOG::equals).count();
    }

    private long rowsOnDisk(String id) throws IOException {
        return fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME).stream()
                .filter(pk -> pk.getValue().equals(id)).count();
    }

    private AdminCollectionUsageEntry storedRow(String indexKey) throws Exception {
        final var pk = cache.getPkIndexCollectionUsage(
                AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, indexKey));
        final var entry = fs.getById(pk);
        final var data = entry.getData();
        data.addProperty(Globals.PK_FIELD, entry.get_id());
        return AdminCollectionUsageEntry.fromJsonObject(data);
    }

    @Test
    public void test_three_hundred_counters_are_one_write_ahead_log_record() throws Exception {
        AdminOperationHelper.flushCollectionUsage(List.of(touched(AccessKind.COLLECTION, null)));
        final var counters = new ArrayList<UsageCounter>();
        for (var i = 0; i < 300; i++) {
            counters.add(touched(AccessKind.FIELD_INDEX, "field" + i));
        }
        final var before = usageLogForces();

        AdminOperationHelper.flushCollectionUsage(counters);

        assertEquals(1, usageLogForces() - before);
        for (var i = 0; i < 300; i += 37) {
            assertEquals(1,
                    rowsOnDisk(AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "field" + i)));
        }
    }

    @Test
    public void test_a_second_flush_replaces_existing_rows_and_inserts_new_ones() throws Exception {
        final var first = touched(AccessKind.FIELD_INDEX, "kept");
        AdminOperationHelper.flushCollectionUsage(List.of(first));
        touched(AccessKind.FIELD_INDEX, "kept");
        final var fresh = touched(AccessKind.FIELD_INDEX, "fresh");

        AdminOperationHelper.flushCollectionUsage(List.of(first, fresh));

        assertEquals(1, rowsOnDisk(AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "kept")));
        assertEquals(2L, storedRow("kept").getAccessCount());
        assertEquals(1L, storedRow("fresh").getAccessCount());
    }

    @Test
    public void test_counters_sharing_a_row_id_write_the_last_one() throws Exception {
        final var collection = touched(AccessKind.COLLECTION, null);
        final var pkIndex = touched(AccessKind.PK_INDEX, null);
        touched(AccessKind.PK_INDEX, null);

        AdminOperationHelper.flushCollectionUsage(List.of(collection, pkIndex));

        assertEquals(1, rowsOnDisk(AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "")));
        assertEquals(AccessKind.PK_INDEX, storedRow("").getKind());
        assertEquals(2L, storedRow("").getAccessCount());
    }

    @Test
    public void test_a_dropped_collection_or_a_forgotten_counter_writes_no_row() throws Exception {
        memoryManagement.recordAccess(AccessKind.COLLECTION, "noSuchDb", "noSuchColl", null);
        final var unregistered = memoryManagement.getCounter(AccessKind.COLLECTION, "noSuchDb", "noSuchColl", null);
        final var forgotten = touched(AccessKind.FIELD_INDEX, "forgotten");
        memoryManagement.clearCounter(AccessKind.FIELD_INDEX, TestGlobals.DB, TestGlobals.COLL, "forgotten");
        final var admin = new UsageCounter(AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null, 1L, 1L);

        AdminOperationHelper.flushCollectionUsage(List.of(unregistered, forgotten, admin));

        assertNull(cache.getPkIndexCollectionUsage(AdminCollectionUsageEntry.buildId("noSuchDb", "noSuchColl", "")));
        assertNull(cache.getPkIndexCollectionUsage(
                AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "forgotten")));
        assertNull(cache
                .getPkIndexCollectionUsage(AdminCollectionUsageEntry.buildId(Globals.ADMIN_DB_NAME, "databases", "")));
    }

    @Test
    public void test_a_failed_flush_puts_its_counters_back() throws Exception {
        final var counter = touched(AccessKind.FIELD_INDEX, "requeued");
        try (var helper = mockStatic(AdminOperationHelper.class, Mockito.CALLS_REAL_METHODS)) {
            helper.when(() -> AdminOperationHelper.flushCollectionUsage(anyList()))
                    .thenThrow(new IOException("disk full"));

            assertThrows(IOException.class,
                    () -> EventProcessorHelper.processMaintenance(new UsageProfileFlushEvent()));
        }

        final var dirty = memoryManagement.takeDirtyUsage();
        assertEquals(List.of(counter), dirty, "a counter whose flush failed is never lost");
        AdminOperationHelper.flushCollectionUsage(dirty);
        assertNotNull(storedRow("requeued"));
    }
}
