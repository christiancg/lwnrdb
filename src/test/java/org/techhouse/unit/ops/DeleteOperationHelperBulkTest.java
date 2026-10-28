package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DeleteOperationHelperBulkTest {
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final List<String> dirtied = new CopyOnWriteArrayList<>();
    private final DirtySink sink = (dbName, collName) -> dirtied.add(dbName + "|" + collName);
    private final BackgroundTaskManager taskManager = new BackgroundTaskManager();
    private BackgroundTaskManager originalTaskManager;
    private IntConsumer originalHalt;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(DeleteOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", taskManager);
        originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        locks.lock(DB, COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(DB, COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String... ids) throws Exception {
        for (final var id : ids) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, id);
            object.addProperty("payload", "value-" + id);
            final var request = new SaveRequest(DB, COLL);
            request.setObject(object);
            request.set_id(id);
            assertNotNull(SaveOperationHelper.executeSave(request));
        }
    }

    private List<String> residentIds() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(DB, COLL).stream().map(PkIndexEntry::getValue).toList();
    }

    private List<String> persistedIds() throws Exception {
        return fs.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::getValue).sorted().toList();
    }

    private List<EntityEvent> queuedDeletes() throws Exception {
        return TestUtils.queuedBackgroundEvents(taskManager).stream().filter(EntityEvent.class::isInstance)
                .map(EntityEvent.class::cast).filter(event -> event.getType() == EventType.DELETED).toList();
    }

    @Test
    public void test_absent_and_repeated_ids_are_skipped_and_the_rest_deleted_in_one_record() throws Exception {
        save("a", "b", "c", "d");
        try (var recorder = RecordingForcer.install(true)) {
            final var deleted = DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("b", "missing", "d", "b"),
                    sink, true);

            assertEquals(List.of("b", "d"), deleted);
            assertEquals(1, recorder.forcedNames().stream().filter(name -> name.equals(COLL + ".wal")).count(),
                    "one write-ahead log record for the whole batch: " + recorder.forcedNames());
        }
        assertEquals(List.of("a", "c"), residentIds());
        assertEquals(List.of("a", "c"), persistedIds());
        assertEquals(List.of(DB + "|" + COLL), dirtied);
    }

    @Test
    public void test_each_deleted_id_gets_one_pending_mark_and_one_deleted_event() throws Exception {
        save("a", "b", "c");
        final var rows = new ArrayList<PkIndexEntry>();
        for (final var row : cache.getPkIndexAndLoadIfNecessary(DB, COLL)) {
            rows.add(row.detachedCopy());
        }
        pendingIndexWrites.clearCollection(DB, COLL);

        DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("a", "c"), sink, false);

        final var events = queuedDeletes();
        assertEquals(List.of("a", "c"), events.stream().map(EntityEvent::getId).toList());
        final var generation = events.getFirst().getPendingGeneration();
        assertTrue(events.stream().allMatch(event -> event.getPendingGeneration() == generation));
        assertEquals(rows.getFirst().getPage(), events.getFirst().getEntity().page());
        assertEquals(rows.getFirst().getLength(), events.getFirst().getEntity().byteSize());
        assertTrue(pendingIndexWrites.idsFor(DB, COLL).containsAll(List.of("a", "c")));
        assertFalse(pendingIndexWrites.idsFor(DB, COLL).contains("b"));
    }

    @Test
    public void test_page_occupancy_drops_by_the_deleted_rows() throws Exception {
        save("a", "b", "c");
        final var rows = cache.getPkIndexAndLoadIfNecessary(DB, COLL);
        final var page = rows.getFirst().getPage();
        final var before = cache.getAdminPageEntry(DB, COLL, page);
        final var sizeBefore = before.getPageSize();
        final var countBefore = before.getEntryCount();
        final var freed = rows.get(0).getLength() + rows.get(1).getLength();

        DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("a", "b"), sink, true);

        final var after = cache.getAdminPageEntry(DB, COLL, page);
        assertEquals(sizeBefore - freed, after.getPageSize());
        assertEquals(countBefore - 2, after.getEntryCount());
    }

    @Test
    public void test_a_page_the_batch_empties_is_truncated_and_its_row_kept() throws Exception {
        save("a", "b", "c");
        final var page = cache.getPkIndexAndLoadIfNecessary(DB, COLL).getFirst().getPage();

        DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("a", "b", "c"), sink, true);

        assertEquals(0L, fs.pageFileLength(DB, COLL, page));
        final var row = cache.getAdminPageEntry(DB, COLL, page);
        assertNotNull(row, "the page row is kept so first-fit reuses the page");
        assertEquals(0L, row.getPageSize());
        assertEquals(0, row.getEntryCount());
        assertEquals(List.of(), residentIds());
    }

    @Test
    public void test_deleted_documents_leave_the_document_cache() throws Exception {
        save("a", "b");
        for (final var row : cache.getPkIndexAndLoadIfNecessary(DB, COLL)) {
            cache.getById(DB, COLL, row);
        }
        final var resident = cache.userCache().getCachedCollection(DB, COLL);
        assertTrue(resident.containsKey("a"));

        DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("a"), sink, true);

        assertFalse(resident.containsKey("a"));
        assertTrue(resident.containsKey("b"));
    }

    @Test
    public void test_a_batch_that_names_no_stored_id_writes_nothing() throws Exception {
        save("a");

        final var deleted = DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("x", "y"), sink, true);

        assertEquals(List.of(), deleted);
        assertEquals(List.of("a"), residentIds());
        assertEquals(List.of(), queuedDeletes());
        assertEquals(List.of(), dirtied);
    }

    @Test
    public void test_a_throw_at_the_commit_point_leaves_the_pk_list_intact() throws Exception {
        save("a", "b", "c");
        pendingIndexWrites.clearCollection(DB, COLL);
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt", (IntConsumer) _ -> {
            throw new IllegalStateException("halted at the commit point");
        });
        faultInjector.arm(FaultPoint.PK_ROW_COMMITTED, FaultAction.HALT, Cache.getCollectionIdentifier(DB, COLL));

        assertThrows(IllegalStateException.class,
                () -> DeleteOperationHelper.executeBulkDelete(DB, COLL, List.of("a", "b"), sink, true));

        assertEquals(List.of("a", "b", "c"), residentIds());
        assertFalse(pendingIndexWrites.idsFor(DB, COLL).contains("a"));
        assertEquals(List.of(), queuedDeletes());
        assertEquals(List.of(), dirtied);
    }
}
