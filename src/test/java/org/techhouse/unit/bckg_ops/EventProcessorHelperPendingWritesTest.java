package org.techhouse.unit.bckg_ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventProcessorHelperPendingWritesTest {

    private PendingIndexWrites pendingIndexWrites;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.standardTearDown();
    }

    private static DbEntry entryWithId(String id) {
        final var data = new JsonObject();
        data.addProperty("_id", id);
        data.addProperty("myField", "myValue");
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
    }

    private EntityEvent markedEvent(String id) {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, id);
        return new EntityEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, entryWithId(id));
    }

    private boolean isPending(String id) {
        return pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains(id);
    }

    @Test
    public void test_an_interrupted_index_rebuild_leaves_the_write_pending() {
        final var event = markedEvent("interrupted");
        try (var mocked = mockStatic(IndexHelper.class)) {
            mocked.when(() -> IndexHelper.updateIndexes(anyString(), anyString(), anyString()))
                    .thenThrow(new InterruptedException("stopped"));
            Assertions.assertThrows(InterruptedException.class, () -> EventProcessorHelper.processEvent(event));
        }
        Assertions.assertTrue(isPending("interrupted"),
                "an abandoned rebuild must leave the id pending so reads keep reconciling it");
        Assertions.assertFalse(Thread.currentThread().isInterrupted(),
                "processEvent propagates the InterruptedException rather than setting the flag itself");
    }

    @Test
    public void test_a_failed_index_rebuild_leaves_the_write_pending() {
        final var event = markedEvent("failed");
        try (var mocked = mockStatic(IndexHelper.class)) {
            mocked.when(() -> IndexHelper.updateIndexes(anyString(), anyString(), anyString()))
                    .thenThrow(new IOException("disk full"));
            Assertions.assertThrows(IOException.class, () -> EventProcessorHelper.processEvent(event));
        }
        Assertions.assertTrue(isPending("failed"));
    }

    @Test
    public void test_a_failed_entry_count_update_leaves_the_write_pending() {
        final var event = markedEvent("counted");
        try (var ignoredIndexes = mockStatic(IndexHelper.class); var admin = mockStatic(AdminOperationHelper.class)) {
            admin.when(() -> AdminOperationHelper.getCollectionEntry(anyString(), anyString())).thenCallRealMethod();
            admin.when(() -> AdminOperationHelper.updateEntryCount(anyString(), anyString(), any(), any()))
                    .thenThrow(new IOException("disk full"));
            Assertions.assertThrows(IOException.class, () -> EventProcessorHelper.processEvent(event));
        }
        Assertions.assertTrue(isPending("counted"));
    }

    @Test
    public void test_a_completed_event_clears_the_pending_write() throws Exception {
        final var event = markedEvent("done");
        EventProcessorHelper.processEvent(event);
        Assertions.assertFalse(isPending("done"));
    }

    @Test
    public void test_a_dropped_collection_still_clears_the_pending_write() throws Exception {
        final var event = markedEvent("dropped");
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        EventProcessorHelper.processEvent(event);
        Assertions.assertFalse(isPending("dropped"),
                "a collection that no longer exists is an absence, not an abandoned rebuild");
    }

    @Test
    public void test_a_failed_group_leaves_every_id_in_the_group_pending() {
        final var batch = List.of(markedEvent("group-a"), markedEvent("group-b"));
        try (var mocked = mockStatic(IndexHelper.class)) {
            mocked.when(() -> IndexHelper.bulkUpdateIndexes(anyString(), anyString(), any()))
                    .thenThrow(new IOException("disk full"));
            Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processBatch(List.copyOf(batch)));
        }
        Assertions.assertTrue(isPending("group-a"));
        Assertions.assertTrue(isPending("group-b"));
    }

    @Test
    public void test_earlier_ids_in_a_group_clear_even_when_a_later_entry_count_update_fails() {
        final var batch = List.of(markedEvent("group-a"), markedEvent("group-b"), markedEvent("group-c"));
        try (var ignoredIndexes = mockStatic(IndexHelper.class); var admin = mockStatic(AdminOperationHelper.class)) {
            admin.when(() -> AdminOperationHelper.getCollectionEntry(anyString(), anyString())).thenCallRealMethod();
            admin.when(() -> AdminOperationHelper.updateEntryCount(anyString(), anyString(), any(),
                    argThat(entry -> entry != null && "group-c".equals(entry.get_id()))))
                    .thenThrow(new IOException("disk full"));
            Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processBatch(List.copyOf(batch)));
        }
        Assertions.assertFalse(isPending("group-a"), "a fully rebuilt and counted id is reconciled");
        Assertions.assertFalse(isPending("group-b"), "a later failure must not un-reconcile an earlier id");
        Assertions.assertTrue(isPending("group-c"), "the id whose count update failed stays pending");
    }

    @Test
    public void test_a_failed_bulk_event_leaves_inserted_and_updated_ids_pending() {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "bulk-new");
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "bulk-old");
        final var event = new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL, List.of(entryWithId("bulk-new")),
                List.of(entryWithId("bulk-old")));
        try (var mocked = mockStatic(IndexHelper.class)) {
            mocked.when(() -> IndexHelper.bulkUpdateIndexes(anyString(), anyString(), any()))
                    .thenThrow(new IOException("disk full"));
            Assertions.assertThrows(IOException.class, () -> EventProcessorHelper.processEvent(event));
        }
        Assertions.assertTrue(isPending("bulk-new"));
        Assertions.assertTrue(isPending("bulk-old"));
    }

    @Test
    public void test_clear_collection_removes_every_pending_id() {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "one");
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "two");
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_clear_collection_leaves_the_dirty_marker_for_reindex_to_retire() {
        final var fs = IocContainer.get(org.techhouse.fs.FileSystem.class);
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "marked");
        Assertions.assertFalse(fs.listDirtyIndexCollections().isEmpty());

        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);

        Assertions.assertFalse(fs.listDirtyIndexCollections().isEmpty(),
                "clearCollection drops the in-memory marks; REINDEX is what clears the on-disk marker beside them");
    }

    @Test
    public void test_clear_collection_on_an_unmarked_collection_is_a_no_op() {
        Assertions.assertDoesNotThrow(() -> pendingIndexWrites.clearCollection(TestGlobals.DB, "never-marked"));
    }
}
