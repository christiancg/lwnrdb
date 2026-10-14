package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ex.PartialBulkUpdateException;
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

    private static File pageFile(long page) {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + page + Globals.DB_FILE_EXTENSION);
    }

    private static BulkSaveRequest bulk(JsonObject... objects) {
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(objects));
        return bulk;
    }

    private void saveTheDocumentTheBulkWillUpdate() throws Exception {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(document(UPDATED_ID, "old"));
        save.set_id(UPDATED_ID);
        assertNotNull(SaveOperationHelper.executeSave(save));
        pending.clear(TestGlobals.DB, TestGlobals.COLL, UPDATED_ID, 0L);
        taskQueue().clear();
    }

    private void blockThePageTheInsertHalfWillTarget() {
        final var firstPage = cache.getAdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0);
        assertNotNull(firstPage, "the first save must have recorded page metadata");
        firstPage.setPageSize(Configuration.getInstance().getMaxPageSize());
        assertTrue(pageFile(1).mkdirs(), "the test needs the page the insert half selects to be unwritable");
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
        assertTrue(pageFile(1).delete(), "the seeded page must exist before it is replaced");
        assertTrue(pageFile(1).mkdir(), "the second entry's page must be unwritable for this test to inject a failure");
    }

    private LinkedBlockingQueue<?> taskQueue() throws Exception {
        return TestUtils.getPrivateField(stubTaskManager, "queue", LinkedBlockingQueue.class);
    }

    private List<BulkEntityEvent> queuedBulkEvents() throws Exception {
        return taskQueue().stream().filter(BulkEntityEvent.class::isInstance).map(BulkEntityEvent.class::cast).toList();
    }

    private static List<String> idsOf(List<EntityRef> entries) {
        return entries.stream().map(EntityRef::id).toList();
    }

    private String cachedValueOf(String id) throws Exception {
        final var entries = cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of(id));
        assertEquals(1, entries.size(), "the collection must still hold " + id);
        return entries.getFirst().getData().get(VALUE_FIELD).asJsonString().getValue();
    }

    private String storedValueOfUpdated() throws Exception {
        final var onDisk = fileSystem.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0).get(UPDATED_ID);
        assertNotNull(onDisk, "the page must still hold " + UPDATED_ID);
        return onDisk.getData().get(VALUE_FIELD).asJsonString().getValue();
    }

    @Test
    public void test_a_failed_insert_half_still_publishes_the_committed_update() throws Exception {
        saveTheDocumentTheBulkWillUpdate();
        blockThePageTheInsertHalfWillTarget();

        final var failure = assertThrows(PartialBulkSaveException.class,
                () -> SaveOperationHelper
                        .executeBulkSave(bulk(document(UPDATED_ID, "new"), document(INSERTED_ID, "x"))),
                "a bulk save whose insert half cannot write its page must surface the failure");

        final var events = queuedBulkEvents();
        assertAll(
                () -> assertEquals("new", storedValueOfUpdated(),
                        "the update half commits before the insert half runs, so it is durable either way"),
                () -> assertEquals("new", cachedValueOf(UPDATED_ID),
                        "a committed update left out of the cache is answered from the pre-update copy until eviction"),
                () -> assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(UPDATED_ID),
                        "without the pending mark an index-backed read trusts the entry under the superseded value"),
                () -> assertEquals(1, events.size(), "the committed update must reach background index maintenance"),
                () -> assertEquals(List.of(UPDATED_ID), idsOf(events.getFirst().getUpdated())),
                () -> assertEquals(List.of(), idsOf(events.getFirst().getInserted()),
                        "the insert half rolled its appends back, so nothing was inserted"),
                () -> assertEquals(List.of(UPDATED_ID),
                        cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                                .map(PkIndexEntry::getValue).toList(),
                        "the id the insert half could not write must not appear in the pk index"),
                () -> assertEquals(List.of(UPDATED_ID), failure.committed().getUpdated(),
                        "the update half landed, so the caller must fire its triggers and replicate it"),
                () -> assertEquals(List.of(), failure.committed().getInserted(),
                        "the insert half was truncated back, so nothing inserted may be reported as committed"));
    }

    @Test
    public void test_a_successful_bulk_save_publishes_exactly_once() throws Exception {
        saveTheDocumentTheBulkWillUpdate();

        assertNotNull(
                SaveOperationHelper.executeBulkSave(bulk(document(UPDATED_ID, "new"), document(INSERTED_ID, "x"))));

        final var events = queuedBulkEvents();
        assertEquals(1, events.size(), "the happy path must submit exactly one bulk event");
        assertEquals(List.of(UPDATED_ID), idsOf(events.getFirst().getUpdated()));
        assertEquals(List.of(INSERTED_ID), idsOf(events.getFirst().getInserted()));
        assertEquals("new", cachedValueOf(UPDATED_ID));
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).containsAll(Set.of(UPDATED_ID, INSERTED_ID)));
    }

    @Test
    public void test_a_mid_batch_update_failure_still_publishes_the_committed_prefix() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        blockThePageTheSecondUpdateWillTarget();

        final var failure = assertThrows(PartialBulkSaveException.class,
                () -> SaveOperationHelper.executeBulkSave(bulk(document(KEPT_ID, "new"), document(FAILING_ID, "new"))),
                "a bulk save whose second update cannot write its page must surface the failure");

        final var events = queuedBulkEvents();
        assertAll(
                () -> assertEquals("new", cachedValueOf(KEPT_ID),
                        "the entry updated before the failing one is durable on disk and must not be served stale"),
                () -> assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(KEPT_ID),
                        "without the pending mark an index-backed read trusts the entry under the superseded value"),
                () -> assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(FAILING_ID),
                        "the entry that never landed on disk must not be marked as a pending index write"),
                () -> assertEquals(1, events.size(), "the committed prefix must reach background index maintenance"),
                () -> assertEquals(List.of(KEPT_ID), idsOf(events.getFirst().getUpdated())),
                () -> assertEquals(List.of(KEPT_ID), failure.committed().getUpdated(),
                        "the caller fires triggers and replicates exactly what landed, so it must be told what that is"),
                () -> assertEquals(List.of(), failure.committed().getInserted()),
                () -> assertInstanceOf(PartialBulkUpdateException.class, failure.getCause(),
                        "the page failure must stay visible as the cause"));
    }

    @Test
    public void test_a_failure_before_anything_landed_throws_the_original_exception() throws Exception {
        final var failing = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document(FAILING_ID, "old"));
        failing.setPage(1);
        fileSystem.insertIntoCollection(failing);
        blockThePageTheSecondUpdateWillTarget();

        final var failure = assertThrows(Exception.class,
                () -> SaveOperationHelper.executeBulkSave(bulk(document(FAILING_ID, "new"))));

        assertFalse(failure instanceof PartialBulkSaveException,
                "a bulk save that committed nothing has nothing to fire triggers for or replicate");
    }

    @Test
    public void test_a_mid_batch_update_failure_applies_the_size_delta_of_its_committed_prefix() throws Exception {
        seedTheDocumentsTheBulkWillUpdate();
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, pageFile(0).length());
        blockThePageTheSecondUpdateWillTarget();

        assertThrows(PartialBulkSaveException.class, () -> SaveOperationHelper.executeBulkSave(
                bulk(document(KEPT_ID, "a value long enough to grow the page"), document(FAILING_ID, "new"))));

        final var inMemorySize = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(p -> p.getPage() == 0).mapToLong(AdminPageEntry::getPageSize).sum();
        assertEquals(pageFile(0).length(), inMemorySize,
                "the committed growth must reach the in-memory page size before any later placement");
    }
}
