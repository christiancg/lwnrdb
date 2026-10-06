package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PendingMarkBeforeWriteTest {
    private static final String ID = "marked";
    private static final String OTHER = "other";
    private static final String DIRTY_KEY = TestGlobals.DB + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;

    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BackgroundTaskManager originalSaveTaskManager;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalSaveTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", stubTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        writer.shutdownNow();
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", originalSaveTaskManager);
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        fs.clearIndexesDirty(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("v", value);
        return object;
    }

    private static SaveRequest saveOf(String id, String value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        return request;
    }

    private static DeleteRequest deleteOfTheMarkedId() {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(ID);
        return request;
    }

    private static File pageZero() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + 0L + Globals.DB_FILE_EXTENSION);
    }

    private void assertMarkedWhileThePageWriteIsBlocked(Callable<?> write) throws Exception {
        final var pageLock = FileLocks.lockFor(pageZero()).writeLock();
        pageLock.lock();
        final Future<?> running;
        try {
            running = writer.submit(write);
            waitUntilPending();
            assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ID),
                    "the id must be pending before its page write can land");
            assertTrue(fs.listDirtyIndexCollections().contains(DIRTY_KEY),
                    "a kill during the page write must leave the index-dirty marker behind");
            assertFalse(running.isDone(), "the write must still be blocked on the page");
        } finally {
            pageLock.unlock();
        }
        running.get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("BusyWait")
    private void waitUntilPending() throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ID) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private void replacePageZeroWithADirectory() {
        assertTrue(pageZero().delete(), "page 0 must exist before it is replaced");
        assertTrue(pageZero().mkdir(), "page 0 must become unwritable for this test to inject a failure");
    }

    @Test
    public void test_a_save_marks_the_id_before_the_page_write() throws Exception {
        assertMarkedWhileThePageWriteIsBlocked(() -> SaveOperationHelper.executeSave(saveOf(ID, "new")));
    }

    @Test
    public void test_an_update_marks_before_the_page_write() throws Exception {
        SaveOperationHelper.executeSave(saveOf(ID, "old"));
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);

        assertMarkedWhileThePageWriteIsBlocked(() -> SaveOperationHelper.executeSave(saveOf(ID, "new")));
    }

    @Test
    public void test_a_delete_marks_before_the_page_write() throws Exception {
        SaveOperationHelper.executeSave(saveOf(ID, "old"));
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);

        assertMarkedWhileThePageWriteIsBlocked(() -> DeleteOperationHelper.executeDelete(deleteOfTheMarkedId()));
    }

    @Test
    public void test_a_failed_save_leaves_nothing_pending() {
        replacePageZeroWithADirectory();

        assertThrows(Exception.class, () -> SaveOperationHelper.executeSave(saveOf(ID, "new")));

        assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ID));
        assertFalse(fs.listDirtyIndexCollections().contains(DIRTY_KEY),
                "a write that never landed must not leave a REINDEX warning behind");
    }

    @Test
    public void test_a_failed_delete_leaves_nothing_pending() throws Exception {
        SaveOperationHelper.executeSave(saveOf(ID, "old"));
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        fs.clearIndexesDirty(TestGlobals.DB, TestGlobals.COLL);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        replacePageZeroWithADirectory();

        assertThrows(Exception.class, () -> DeleteOperationHelper.executeDelete(deleteOfTheMarkedId()));

        assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(ID));
    }

    @Test
    public void test_a_bulk_save_marks_every_id_once() throws Exception {
        SaveOperationHelper.executeSave(saveOf(OTHER, "old"));
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(ID, "new"), document(OTHER, "new")));

        SaveOperationHelper.executeBulkSave(bulk);

        final LinkedBlockingQueue<?> queue = TestUtils.getPrivateField(stubTaskManager, "queue",
                LinkedBlockingQueue.class);
        final var events = queue.stream().filter(BulkEntityEvent.class::isInstance).map(BulkEntityEvent.class::cast)
                .toList();
        assertEquals(1, events.size());
        final var event = events.getFirst();
        pending.clear(TestGlobals.DB, TestGlobals.COLL, List.of(ID, OTHER), event.getPendingGeneration());
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "an id marked twice stays pending after its one index event, keeping reads on the slow path");
    }

    @Test
    public void test_a_bulk_save_that_lands_nothing_leaves_nothing_pending() {
        replacePageZeroWithADirectory();
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document(ID, "new"), document(OTHER, "new")));

        assertThrows(Exception.class, () -> SaveOperationHelper.executeBulkSave(bulk));

        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }
}
