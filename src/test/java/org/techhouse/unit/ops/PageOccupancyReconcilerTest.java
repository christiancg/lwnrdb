package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

public class PageOccupancyReconcilerTest {
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
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (originalTaskManager != null) {
            TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
            pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "value-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private AdminPageEntry row(long page) {
        final var rows = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        return rows == null ? null : rows.stream().filter(r -> r.getPage() == page).findFirst().orElse(null);
    }

    private AdminPageEntry existingRow(long page) {
        final var found = row(page);
        assertNotNull(found, "page " + page + " has no row");
        return found;
    }

    private long firstPageFileLength() throws Exception {
        return fs.pageFileLengths(TestGlobals.DB, TestGlobals.COLL).get(0L);
    }

    private File persistedRowsFolder() {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, TestGlobals.DB,
                TestGlobals.COLL);
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR + pagesCollName);
    }

    @Test
    public void test_missing_row_for_a_page_file_is_rebuilt() throws Exception {
        save("a");
        save("b");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        final var rebuilt = row(0);
        assertNotNull(rebuilt);
        assertEquals(2, rebuilt.getEntryCount());
        assertEquals(firstPageFileLength(), rebuilt.getPageSize());
        final var files = persistedRowsFolder().listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
        assertNotNull(files);
        assertTrue(files.length > 0, "the rebuilt row must reach disk, not only the cache");
    }

    @Test
    public void test_row_with_wrong_size_is_corrected() throws Exception {
        save("a");
        existingRow(0).setPageSize(1L);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(firstPageFileLength(), existingRow(0).getPageSize());
        assertEquals(1, existingRow(0).getEntryCount());
    }

    @Test
    public void test_row_for_a_page_file_that_no_longer_exists_is_emptied() throws Exception {
        save("a");
        final var phantom = new AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 9L);
        phantom.setEntryCount(3);
        phantom.setPageSize(500L);
        cache.addAdminPageEntries(TestGlobals.DB, TestGlobals.COLL, phantom);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(0, existingRow(9).getEntryCount());
        assertEquals(0L, existingRow(9).getPageSize());
    }

    @Test
    public void test_consistent_collection_is_not_rewritten() throws Exception {
        save("a");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertFalse(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_rows_record_live_bytes_not_the_file_length() throws Exception {
        save("a");
        save("b");
        save("c");
        final var removed = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(pk -> pk.getValue().equals("b")).findFirst().orElseThrow();
        fs.deleteFromCollection(removed);
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(firstPageFileLength() - removed.getLength(), existingRow(0).getPageSize(),
                "a hole is dead space: the row counts only the records the pk index names");
        assertEquals(2, existingRow(0).getEntryCount());
    }

    @Test
    public void test_an_empty_collection_needs_nothing() throws Exception {
        assertFalse(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_reconcile_all_covers_every_registered_collection() throws Exception {
        save("a");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        PageOccupancyReconciler.reconcileAll();

        assertNotNull(row(0));
        assertEquals(firstPageFileLength(), existingRow(0).getPageSize());
    }

    private File firstUserPage() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-0.dat");
    }

    @Test
    public void test_a_torn_page_tail_is_healed_so_the_next_insert_is_visible_to_a_scan() throws Exception {
        save("before");
        Files.writeString(firstUserPage().toPath(), "{\"_id\":\"torn\",\"pay", StandardOpenOption.APPEND);

        PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        save("after");
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);

        final var scanned = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(scanned.containsKey("before"));
        assertTrue(scanned.containsKey("after"), "an insert after the heal must not share a line with torn bytes");
        assertFalse(scanned.containsKey("torn"));
        assertEquals(firstUserPage().length(), existingRow(0).getPageSize());
    }

    private void stubTheTaskManager() throws Exception {
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", stubTaskManager);
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
        stubTheTaskManager();
        save(KEPT);
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
        stubTheTaskManager();
        save(KEPT);
        leaveAnUnindexedRecord();

        PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ORPHAN),
                "an index-backed read must re-derive the adopted record before the worker indexes it");
        final var updates = queuedUpdates();
        assertEquals(1, updates.size());
        final var adopted = updates.getFirst().getEntity();
        assertEquals(ORPHAN, adopted.id());
        assertEquals(adopted.byteSize(), adopted.previousByteSize(),
                "the reconcile already counts the record, so its event must not move the page size again");
    }

    @Test
    public void test_rows_are_rebuilt_after_an_adoption_even_when_their_sizes_already_agree() throws Exception {
        stubTheTaskManager();
        save(KEPT);
        leaveAnUnindexedRecord();
        firstPageRow().setPageSize(firstUserPage().length());

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(2, firstPageRow().getEntryCount());
        assertEquals(firstUserPage().length(), firstPageRow().getPageSize());
    }

    @Test
    public void test_nothing_is_queued_when_nothing_was_adopted() throws Exception {
        stubTheTaskManager();
        save(KEPT);

        PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(queuedUpdates().isEmpty());
    }
}
