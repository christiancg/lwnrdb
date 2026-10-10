package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ex.PartialBulkUpdateException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkSaveMidBatchUpdateFailureTest {
    private static final String KEPT_ID = "kept";
    private static final String FAILING_ID = "failing";
    private static final String VALUE_FIELD = "v";

    private Cache cache;
    private ResourceLocking locks;
    private PendingIndexWrites pending;
    private FileSystem fileSystem;
    private BackgroundTaskManager stubTaskManager;
    private BackgroundTaskManager originalTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        locks = IocContainer.get(ResourceLocking.class);
        pending = IocContainer.get(PendingIndexWrites.class);
        fileSystem = IocContainer.get(FileSystem.class);
        originalTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", stubTaskManager);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        locks.release(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", originalTaskManager);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty(VALUE_FIELD, value);
        return object;
    }

    private File pageOneFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + (long) 1 + Globals.DB_FILE_EXTENSION);
    }

    private void seedTheDocumentsTheBulkWillUpdate() throws Exception {
        final var kept = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document(KEPT_ID, "old"));
        kept.setPage(0);
        fileSystem.insertIntoCollection(kept);
        final var failing = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document(FAILING_ID, "old"));
        failing.setPage(1);
        fileSystem.insertIntoCollection(failing);
    }

    private void blockThePageTheSecondUpdateWillTarget() {
        assertTrue(pageOneFile().delete(), "the seeded page must exist before it is replaced");
        assertTrue(pageOneFile().mkdir(),
                "the second entry's page must be unwritable for this test to inject a failure");
    }

    private static BulkSaveRequest failingBulk() {
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(KEPT_ID, "new"), document(FAILING_ID, "new")));
        return bulk;
    }

    private void runTheFailingBulkSave() {
        assertThrows(PartialBulkSaveException.class, () -> SaveOperationHelper.executeBulkSave(failingBulk()),
                "a bulk save whose second update cannot write its page must surface the failure");
    }

    private LinkedBlockingQueue<?> taskQueue() throws Exception {
        return TestUtils.getPrivateField(stubTaskManager, "queue", LinkedBlockingQueue.class);
    }

    private List<BulkEntityEvent> queuedBulkEvents() throws Exception {
        return taskQueue().stream().filter(BulkEntityEvent.class::isInstance).map(BulkEntityEvent.class::cast).toList();
    }

    private String cachedValueOfKept() throws Exception {
        final var entries = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(KEPT_ID));
        assertEquals(1, entries.size(), "the collection must still hold " + KEPT_ID);
        return entries.getFirst().getData().get(VALUE_FIELD).asJsonString().getValue();
    }

    @Test
    public void test_a_mid_batch_update_failure_still_refreshes_the_cache_for_the_committed_prefix() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        runTheFailingBulkSave();

        assertEquals("new", cachedValueOfKept(),
                "the entry updated before the failing one is durable on disk and must not be served stale");
    }

    @Test
    public void test_a_mid_batch_update_failure_still_marks_the_committed_prefix_pending() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        runTheFailingBulkSave();

        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(KEPT_ID),
                "without the pending mark an index-backed read trusts the entry under the superseded value");
    }

    @Test
    public void test_a_mid_batch_update_failure_still_submits_the_index_event_for_the_committed_prefix()
            throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        runTheFailingBulkSave();

        final var events = queuedBulkEvents();
        assertEquals(1, events.size(), "the committed prefix must reach background index maintenance");
        assertEquals(List.of(KEPT_ID), events.getFirst().getUpdatedEntries().stream().map(DbEntry::get_id).toList());
    }

    @Test
    public void test_a_mid_batch_update_failure_does_not_mark_the_failing_entry_pending() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        runTheFailingBulkSave();

        assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(FAILING_ID),
                "the entry that never landed on disk must not be marked as a pending index write");
    }

    @Test
    public void test_a_mid_batch_update_failure_throws_with_the_committed_prefix() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        final var failure = assertThrows(PartialBulkSaveException.class,
                () -> SaveOperationHelper.executeBulkSave(failingBulk()));

        assertEquals(List.of(KEPT_ID), failure.committed().getUpdated(),
                "the caller fires triggers and replicates exactly what landed, so it must be told what that is");
        assertEquals(List.of(), failure.committed().getInserted());
        assertInstanceOf(PartialBulkUpdateException.class, failure.getCause(),
                "the page failure must stay visible as the cause");
    }

    @Test
    public void test_a_failure_before_anything_landed_throws_the_original_exception() throws Exception {
        final var failing = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document(FAILING_ID, "old"));
        failing.setPage(1);
        fileSystem.insertIntoCollection(failing);
        blockThePageTheSecondUpdateWillTarget();
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(FAILING_ID, "new")));

        final var failure = assertThrows(Exception.class, () -> SaveOperationHelper.executeBulkSave(bulk));

        assertFalse(failure instanceof PartialBulkSaveException,
                "a bulk save that committed nothing has nothing to fire triggers for or replicate");
    }

    private File pageZeroFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + 0L + Globals.DB_FILE_EXTENSION);
    }

    private long inMemorySizeOfPageZero() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().filter(p -> p.getPage() == 0)
                .mapToLong(AdminPageEntry::getPageSize).sum();
    }

    @Test
    public void test_a_mid_batch_update_failure_applies_the_size_delta_of_its_committed_prefix() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, pageZeroFile().length());
        blockThePageTheSecondUpdateWillTarget();
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(
                List.of(document(KEPT_ID, "a value long enough to grow the page"), document(FAILING_ID, "new")));

        assertThrows(PartialBulkSaveException.class, () -> SaveOperationHelper.executeBulkSave(bulk));

        assertEquals(pageZeroFile().length(), inMemorySizeOfPageZero(),
                "the committed growth must reach the in-memory page size before any later placement");
    }
}
