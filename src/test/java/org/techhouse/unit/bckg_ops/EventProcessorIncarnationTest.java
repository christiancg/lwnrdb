package org.techhouse.unit.bckg_ops;

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
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventProcessorIncarnationTest {
    private static final long CURRENT = 200L;
    private static final long DROPPED = 100L;

    private Cache cache;
    private PendingIndexWrites pendingIndexWrites;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIncarnation(CURRENT);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry entryOnPage(String id, long page) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("payload", new JsonString("some bytes"));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        entry.setPage(page);
        return entry;
    }

    private AdminPageEntry page(long number) {
        final var pages = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        if (pages == null) {
            return null;
        }
        return pages.stream().filter(p -> p.getPage() == number).findFirst().orElse(null);
    }

    private AdminPageEntry firstPage() {
        final var found = page(0);
        Assertions.assertNotNull(found, "page 0 has no row");
        return found;
    }

    private void seedLivePage() throws IOException, InterruptedException {
        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL,
                List.of(entryOnPage("live", 0)), List.of(), CURRENT));
    }

    private static EntityEvent event(EventType type, DbEntry entry, long incarnation) {
        return new EntityEvent(type, TestGlobals.DB, TestGlobals.COLL, entry, incarnation);
    }

    @Test
    public void test_stale_deleted_event_does_not_touch_a_recreated_collections_pages() throws Exception {
        seedLivePage();
        final var before = firstPage();
        final var countBefore = before.getEntryCount();
        final var sizeBefore = before.getPageSize();

        EventProcessorHelper.processEvent(event(EventType.DELETED, entryOnPage("gone", 0), DROPPED));

        Assertions.assertEquals(countBefore, firstPage().getEntryCount());
        Assertions.assertEquals(sizeBefore, firstPage().getPageSize());
    }

    @Test
    public void test_stale_created_event_does_not_insert_a_phantom_page() throws Exception {
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("gone", 5), DROPPED));

        Assertions.assertNull(page(5));
    }

    @Test
    public void test_stale_bulk_event_is_skipped_and_its_pending_marks_cleared() throws Exception {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "gone");

        EventProcessorHelper.processEvent(new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL,
                List.of(entryOnPage("gone", 3)), List.of(), DROPPED));

        Assertions.assertNull(page(3));
        Assertions.assertFalse(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("gone"));
    }

    @Test
    public void test_stale_events_in_a_mixed_batch_are_skipped_while_current_ones_apply() throws Exception {
        final List<Event> batch = List.of(event(EventType.CREATED, entryOnPage("gone", 7), DROPPED),
                event(EventType.CREATED, entryOnPage("kept", 8), CURRENT));

        EventProcessorHelper.processBatch(batch);

        Assertions.assertNull(page(7));
        Assertions.assertNotNull(page(8));
    }

    @Test
    public void test_event_with_matching_incarnation_is_processed() throws Exception {
        seedLivePage();
        final var countBefore = firstPage().getEntryCount();

        EventProcessorHelper.processEvent(event(EventType.DELETED, entryOnPage("live", 0), CURRENT));

        Assertions.assertEquals(countBefore - 1, firstPage().getEntryCount());
    }

    @Test
    public void test_zero_incarnation_on_either_side_is_processed() throws Exception {
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("unstamped", 4), 0L));
        Assertions.assertNotNull(page(4));

        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIncarnation(0L);
        EventProcessorHelper.processEvent(event(EventType.CREATED, entryOnPage("legacy", 6), DROPPED));
        Assertions.assertNotNull(page(6));
    }

    @Test
    public void test_entry_count_refuses_an_incarnation_that_changed_under_the_lock() throws Exception {
        AdminOperationHelper.updateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entryOnPage("raced", 9), DROPPED);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                List.of(entryOnPage("raced-bulk", 10)), DROPPED);

        Assertions.assertNull(page(9));
        Assertions.assertNull(page(10));
    }

    @Test
    public void test_current_incarnation_reads_the_registered_entry() {
        Assertions.assertEquals(CURRENT, CollectionIncarnation.current(TestGlobals.DB, TestGlobals.COLL));
        Assertions.assertEquals(0L, CollectionIncarnation.current(TestGlobals.DB, "neverCreated"));
        Assertions.assertFalse(CollectionIncarnation.isCurrent(TestGlobals.DB, "neverCreated", 0L));
    }
}
