package org.techhouse.unit.bckg_ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventBatchCoalescingTest {
    private static final String OTHER_COLL = "otherColl";

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.addProperty("field", id);
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static int totalEntryCount(String collName) {
        final var pageEntries = IocContainer.get(Cache.class).getAdminPageEntries(TestGlobals.DB, collName);
        return pageEntries == null ? 0 : pageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
    }

    private static void seedCollections() throws IOException, InterruptedException {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, OTHER_COLL));
    }

    @Test
    public void test_batched_creates_count_every_created_id_like_a_bulk_event() throws Exception {
        seedCollections();
        final var batched = new ArrayList<Event>();
        final var inserted = new ArrayList<DbEntry>();
        for (var i = 0; i < 12; i++) {
            batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("id" + i)));
            final var other = entry("id" + i);
            other.setCollectionName(OTHER_COLL);
            inserted.add(other);
        }

        EventProcessorHelper.processBatch(batched);
        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.DB, OTHER_COLL, inserted, List.of()));

        Assertions.assertEquals(12, totalEntryCount(TestGlobals.COLL));
        Assertions.assertEquals(totalEntryCount(OTHER_COLL), totalEntryCount(TestGlobals.COLL),
                "a coalesced batch books a missing page like a bulk event of the same documents");
    }

    @Test
    public void test_a_group_is_booked_once() throws Exception {
        seedCollections();
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 6; i++) {
            batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("once" + i)));
        }
        try (var admin = Mockito.mockStatic(AdminOperationHelper.class, Mockito.CALLS_REAL_METHODS)) {
            EventProcessorHelper.processBatch(batch);

            admin.verify(() -> AdminOperationHelper.bookWritePathPages(ArgumentMatchers.eq(TestGlobals.DB),
                    ArgumentMatchers.eq(TestGlobals.COLL), ArgumentMatchers.argThat(created -> created.size() == 6),
                    ArgumentMatchers.argThat(List::isEmpty), ArgumentMatchers.anyLong()), Mockito.times(1));
        }
        Assertions.assertEquals(6, totalEntryCount(TestGlobals.COLL));
    }

    @Test
    public void test_batch_clears_every_pending_write() throws Exception {
        seedCollections();
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 8; i++) {
            pending.mark(TestGlobals.DB, TestGlobals.COLL, "id" + i);
            batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("id" + i)));
        }

        EventProcessorHelper.processBatch(batch);

        Assertions.assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_batch_spanning_two_collections_counts_each_separately() throws Exception {
        seedCollections();
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 5; i++) {
            batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("a" + i)));
        }
        for (var i = 0; i < 3; i++) {
            final var dbEntry = entry("b" + i);
            dbEntry.setCollectionName(OTHER_COLL);
            batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, OTHER_COLL, dbEntry));
        }

        EventProcessorHelper.processBatch(batch);

        Assertions.assertTrue(totalEntryCount(TestGlobals.COLL) > 0,
                "the first collection group in the batch must be processed");
        Assertions.assertTrue(totalEntryCount(OTHER_COLL) > 0,
                "the second collection group in the batch must be processed too");
    }

    @Test
    public void test_mixed_event_types_batched_match_the_same_documents_as_a_bulk_event() throws Exception {
        seedCollections();

        final var batched = new ArrayList<Event>();
        batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("id0")));
        batched.add(new EntityEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, entry("id0")));
        batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("id1")));
        EventProcessorHelper.processBatch(batched);

        final var inserted = new ArrayList<DbEntry>();
        for (final var id : List.of("id0", "id1")) {
            final var other = entry(id);
            other.setCollectionName(OTHER_COLL);
            inserted.add(other);
        }
        final var updated = entry("id0");
        updated.setCollectionName(OTHER_COLL);
        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.DB, OTHER_COLL, inserted, List.of(updated)));

        Assertions.assertEquals(2, totalEntryCount(TestGlobals.COLL));
        Assertions.assertEquals(totalEntryCount(OTHER_COLL), totalEntryCount(TestGlobals.COLL),
                "batched events and a bulk event of the same documents must agree on the entry count");
    }

    @Test
    public void test_a_create_and_delete_of_one_id_in_a_group_count_every_created_id() throws Exception {
        seedCollections();
        final var batched = new ArrayList<Event>();
        batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("flip")));
        batched.add(new EntityEvent(EventType.DELETED, TestGlobals.DB, TestGlobals.COLL, entry("flip")));
        batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("flip")));
        batched.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("kept")));

        EventProcessorHelper.processBatch(batched);

        Assertions.assertEquals(2, totalEntryCount(TestGlobals.COLL),
                "a missing row counts each distinct created id once and a delete books no delta: the write path owns "
                        + "deltas, and the startup reconciler corrects a row created this way");
    }

    @Test
    public void test_dropped_collection_in_a_batch_clears_pending_and_creates_no_page_metadata() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 4; i++) {
            pending.mark(TestGlobals.DB, TestGlobals.COLL, "ghost" + i);
            batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("ghost" + i)));
        }

        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processBatch(batch));

        Assertions.assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        Assertions.assertNull(IocContainer.get(Cache.class).getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_single_event_batch_behaves_like_the_unbatched_path() throws Exception {
        seedCollections();

        EventProcessorHelper.processBatch(
                List.of(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("solo"))));

        Assertions.assertEquals(1, totalEntryCount(TestGlobals.COLL));
    }

    @Test
    public void test_duplicate_ids_in_one_batch_are_indexed_once_but_counted_per_event() throws Exception {
        seedCollections();
        final var batch = new ArrayList<Event>();
        batch.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry("dup")));
        batch.add(new EntityEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, entry("dup")));

        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processBatch(batch));

        Assertions.assertEquals(1, totalEntryCount(TestGlobals.COLL));
    }
}
