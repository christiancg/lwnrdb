package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The update half of a bulk save is durable before the insert half runs. When the insert half fails,
 * whatever committed must still reach the cache, the pending-write overlay and the background index
 * event, or the collection answers reads with a document the page no longer holds and the field index
 * keeps mapping the superseded value with nothing to warn the operator.
 */
public class BulkSavePartialFailureTest {
    private static final String UPDATED_ID = "u1";
    private static final String INSERTED_ID = "i1";
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

    private File pageFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + (long) 1 + Globals.DB_FILE_EXTENSION);
    }

    private void saveTheDocumentTheBulkWillUpdate() throws Exception {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(document(UPDATED_ID, "old"));
        save.set_id(UPDATED_ID);
        assertNotNull(SaveOperationHelper.executeSave(save));
        pending.clear(TestGlobals.DB, TestGlobals.COLL, UPDATED_ID);
        taskQueue().clear();
    }

    private void blockThePageTheInsertHalfWillTarget() {
        final var firstPage = cache.getAdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0);
        assertNotNull(firstPage, "the first save must have recorded page metadata");
        firstPage.setPageSize(Configuration.getInstance().getMaxPageSize());
        assertTrue(pageFile().mkdirs(), "the test needs the page the insert half selects to be unwritable");
    }

    private void runTheFailingBulkSave() {
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(UPDATED_ID, "new"), document(INSERTED_ID, "x")));
        assertThrows(Exception.class, () -> SaveOperationHelper.executeBulkSave(bulk),
                "a bulk save whose insert half cannot write its page must surface the failure");
    }

    private LinkedBlockingQueue<?> taskQueue() throws Exception {
        return TestUtils.getPrivateField(stubTaskManager, "queue", LinkedBlockingQueue.class);
    }

    private List<BulkEntityEvent> queuedBulkEvents() throws Exception {
        return taskQueue().stream().filter(BulkEntityEvent.class::isInstance).map(BulkEntityEvent.class::cast).toList();
    }

    private String cachedValueOf() throws Exception {
        final var entries = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(BulkSavePartialFailureTest.UPDATED_ID));
        assertEquals(1, entries.size(), "the collection must still hold " + BulkSavePartialFailureTest.UPDATED_ID);
        return entries.getFirst().getData().get(VALUE_FIELD).asJsonString().getValue();
    }

    private String storedValueOf() throws Exception {
        final var onDisk = fileSystem.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0).get(BulkSavePartialFailureTest.UPDATED_ID);
        assertNotNull(onDisk, "the page must still hold " + BulkSavePartialFailureTest.UPDATED_ID);
        return onDisk.getData().get(VALUE_FIELD).asJsonString().getValue();
    }

    @Test
    public void test_a_failed_insert_half_leaves_the_update_durable_on_the_page() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        runTheFailingBulkSave();

        assertEquals("new", storedValueOf(),
                "the update half commits before the insert half runs, so it is durable either way");
    }

    @Test
    public void test_a_failed_insert_half_still_refreshes_the_cache() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        runTheFailingBulkSave();

        assertEquals("new", cachedValueOf(),
                "a committed update left out of the cache is answered from the pre-update copy until eviction");
    }

    @Test
    public void test_a_failed_insert_half_still_marks_the_updated_ids_pending() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        runTheFailingBulkSave();

        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(UPDATED_ID),
                "without the pending mark an index-backed read trusts the entry under the superseded value");
    }

    @Test
    public void test_a_failed_insert_half_still_submits_the_index_event() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        runTheFailingBulkSave();

        final var events = queuedBulkEvents();
        assertEquals(1, events.size(), "the committed update must reach background index maintenance");
        assertEquals(List.of(UPDATED_ID), events.getFirst().getUpdatedEntries().stream().map(DbEntry::get_id).toList());
        assertEquals(List.of(), events.getFirst().getInsertedEntries().stream().map(DbEntry::get_id).toList(),
                "the insert half rolled its appends back, so nothing was inserted");
    }

    @Test
    public void test_a_failed_insert_half_leaves_no_inserted_document_behind() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        runTheFailingBulkSave();

        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(List.of(UPDATED_ID), pkIndex.stream().map(PkIndexEntry::getValue).toList(),
                "the id the insert half could not write must not appear in the pk index");
    }

    @Test
    public void test_a_successful_bulk_save_publishes_exactly_once() throws Exception {
        saveTheDocumentTheBulkWillUpdate();

        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(UPDATED_ID, "new"), document(INSERTED_ID, "x")));
        assertNotNull(SaveOperationHelper.executeBulkSave(bulk));

        final var events = queuedBulkEvents();
        assertEquals(1, events.size(), "the happy path must submit exactly one bulk event");
        assertEquals(List.of(UPDATED_ID), events.getFirst().getUpdatedEntries().stream().map(DbEntry::get_id).toList());
        assertEquals(List.of(INSERTED_ID),
                events.getFirst().getInsertedEntries().stream().map(DbEntry::get_id).toList());
        assertEquals("new", cachedValueOf());
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).containsAll(Set.of(UPDATED_ID, INSERTED_ID)));
    }
}
