package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RecoveredDeleteIndexCleanupTest {
    private static final String FIELD = "tag";
    private static final String KEPT = "kept";
    private static final String GONE = "gone";

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private BackgroundTaskManager originalTaskManager;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", stubTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty(FIELD, "value-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private PkIndexEntry deleteBehindTheIndexesBack(String id) throws Exception {
        final var row = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(entry -> entry.getValue().equals(id)).findFirst().orElseThrow();
        fs.deleteFromCollection(row.detachedCopy());
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        return row;
    }

    private void indexTheField() throws Exception {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private Set<String> indexedIds() throws Exception {
        return fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class).stream()
                .flatMap(entry -> entry.getIds().stream()).collect(Collectors.toSet());
    }

    private List<EntityEvent> queuedUpdates() throws Exception {
        final LinkedBlockingQueue<?> queue = TestUtils.getPrivateField(stubTaskManager, "queue",
                LinkedBlockingQueue.class);
        return queue.stream().filter(EntityEvent.class::isInstance).map(EntityEvent.class::cast)
                .filter(event -> event.getType() == EventType.UPDATED).toList();
    }

    @Test
    public void test_a_recovered_delete_is_marked_pending_and_queued() throws Exception {
        save(KEPT);
        save(GONE);
        final var row = deleteBehindTheIndexesBack(GONE);

        PageOccupancyReconciler.scheduleIndexCleanupFor(List.of(row));

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(GONE),
                "an index-backed read must stop answering the deleted id before the worker removes it");
        final var updates = queuedUpdates();
        assertEquals(1, updates.size());
        final var queued = updates.getFirst().getDbEntry();
        assertEquals(GONE, queued.get_id());
        assertEquals(queued.byteSize(), queued.getPreviousByteSize(),
                "the page rows were rebuilt from the page files, so the cleanup must not move them again");
    }

    @Test
    public void test_after_the_event_runs_the_id_is_in_no_index() throws Exception {
        save(KEPT);
        save(GONE);
        indexTheField();
        assertTrue(indexedIds().contains(GONE));
        final var row = deleteBehindTheIndexesBack(GONE);
        PageOccupancyReconciler.scheduleIndexCleanupFor(List.of(row));

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, GONE);

        assertEquals(Set.of(KEPT), indexedIds());
    }

    @Test
    public void test_an_admin_collection_delete_is_skipped() throws Exception {
        final var adminRow = new PkIndexEntry(Globals.ADMIN_DB_NAME, "users", "someone", 0, 10, 0);
        final var pageRow = new PkIndexEntry(Globals.ADMIN_PAGES_DB_NAME, "admin_users", "row", 0, 10, 0);

        PageOccupancyReconciler.scheduleIndexCleanupFor(List.of(adminRow, pageRow));

        assertTrue(queuedUpdates().isEmpty());
    }

    @Test
    public void test_a_delete_in_an_unregistered_collection_is_skipped() throws Exception {
        final var row = new PkIndexEntry(TestGlobals.DB, "droppedcoll", GONE, 0, 10, 0);

        PageOccupancyReconciler.scheduleIndexCleanupFor(List.of(row));

        assertTrue(queuedUpdates().isEmpty());
        assertFalse(pendingIndexWrites.idsFor(TestGlobals.DB, "droppedcoll").contains(GONE));
    }

    @Test
    public void test_two_deletes_in_one_collection_queue_two_events() throws Exception {
        save(KEPT);
        save(GONE);
        final var first = deleteBehindTheIndexesBack(GONE);
        final var second = deleteBehindTheIndexesBack(KEPT);

        PageOccupancyReconciler.scheduleIndexCleanupFor(List.of(first, second));

        assertEquals(Set.of(GONE, KEPT),
                Set.copyOf(queuedUpdates().stream().map(event -> event.getDbEntry().get_id()).toList()));
    }
}
