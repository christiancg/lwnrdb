package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
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
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageOccupancyReconcilerAdoptionTest {
    private static final String KEPT = "a";
    private static final String ORPHAN = "orphan";
    private static final String ORPHAN_RECORD = "{\"_id\":\"orphan\",\"payload\":\"left by a crash\"}\n";

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

    private void saveKeptDocument() throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, KEPT);
        object.addProperty("payload", "value-" + KEPT);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(KEPT);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private File firstUserPage() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-0.dat");
    }

    private void leaveAnUnindexedRecord() throws Exception {
        Files.writeString(firstUserPage().toPath(), ORPHAN_RECORD, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private AdminPageEntry firstPageRow() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().filter(r -> r.getPage() == 0)
                .findFirst().orElseThrow();
    }

    private List<EntityEvent> queuedUpdates() throws Exception {
        final LinkedBlockingQueue<?> queue = TestUtils.getPrivateField(stubTaskManager, "queue",
                LinkedBlockingQueue.class);
        return queue.stream().filter(EntityEvent.class::isInstance).map(EntityEvent.class::cast)
                .filter(event -> event.getType() == EventType.UPDATED).toList();
    }

    @Test
    public void test_an_unindexed_record_is_adopted_and_answered_by_find_by_id() throws Exception {
        saveKeptDocument();
        leaveAnUnindexedRecord();

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);

        final var pkIds = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream().map(PkIndexEntry::getValue)
                .sorted().toList();
        assertEquals(List.of(KEPT, ORPHAN), pkIds);
        final var whole = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(2, whole.size());
        assertEquals("left by a crash", whole.get(ORPHAN).getData().get("payload").asJsonString().getValue());
    }

    @Test
    public void test_an_adopted_record_is_pending_and_queued_for_its_indexes_with_no_page_delta() throws Exception {
        saveKeptDocument();
        leaveAnUnindexedRecord();

        PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ORPHAN),
                "an index-backed read must re-derive the adopted record before the worker indexes it");
        final var updates = queuedUpdates();
        assertEquals(1, updates.size());
        final var adopted = updates.getFirst().getDbEntry();
        assertEquals(ORPHAN, adopted.get_id());
        assertEquals(adopted.byteSize(), adopted.getPreviousByteSize(),
                "the reconcile already counts the record, so its event must not move the page size again");
    }

    @Test
    public void test_rows_are_rebuilt_after_an_adoption_even_when_their_sizes_already_agree() throws Exception {
        saveKeptDocument();
        leaveAnUnindexedRecord();
        firstPageRow().setPageSize(firstUserPage().length());

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(2, firstPageRow().getEntryCount());
        assertEquals(firstUserPage().length(), firstPageRow().getPageSize());
    }

    @Test
    public void test_nothing_is_queued_when_nothing_was_adopted() throws Exception {
        saveKeptDocument();

        PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(queuedUpdates().isEmpty());
    }
}
