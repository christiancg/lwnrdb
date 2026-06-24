package org.techhouse.unit.bckg_ops;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventProcessorHelperTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void processDatabaseEventDeletionTest() throws IOException, InterruptedException {
        final var databaseEventCreate = new DatabaseEvent(EventType.CREATED, TestGlobals.DB);
        EventProcessorHelper.processEvent(databaseEventCreate);
        final var databaseEventDelete = new DatabaseEvent(EventType.DELETED, TestGlobals.DB);
        EventProcessorHelper.processEvent(databaseEventDelete);
        final var dbEntry = AdminOperationHelper.getDatabaseEntry(TestGlobals.DB);
        Assertions.assertNull(dbEntry);
    }

    @Test
    public void processCollectionEventCreationTest() throws IOException, InterruptedException {
        final var adminEvent = new DatabaseEvent(EventType.CREATED, TestGlobals.DB);
        EventProcessorHelper.processEvent(adminEvent);
        final var collectionEvent = new CollectionEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL);
        EventProcessorHelper.processEvent(collectionEvent);
        final var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
    }

    @Test
    public void processCollectionEventDeletionTest() throws IOException, InterruptedException {
        var collectionEvent = mock(CollectionEvent.class);
        when(collectionEvent.getDbName()).thenReturn(TestGlobals.DB);
        when(collectionEvent.getCollName()).thenReturn(TestGlobals.COLL);
        when(collectionEvent.getType()).thenReturn(EventType.DELETED);
        EventProcessorHelper.processEvent(collectionEvent);
        Assertions
                .assertDoesNotThrow(() -> AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void processProcessEventWithInvalidEventTest() {
        final var event = new Event(EventType.CREATED) {
        };
        Assertions.assertThrows(IllegalStateException.class, () -> EventProcessorHelper.processEvent(event));
    }

    @Test
    public void processBulkEntityEventTest() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        final var testObj = new JsonObject();
        testObj.add("myField", "myValue");
        List<DbEntry> insertedEntries = new ArrayList<>();
        insertedEntries.add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, testObj));
        final var bulkEntityEvent = new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL, insertedEntries,
                new ArrayList<>());
        EventProcessorHelper.processEvent(bulkEntityEvent);
        final var cache = IocContainer.get(Cache.class);
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        final var totalCount = pageEntries == null
                ? 0
                : pageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
        Assertions.assertEquals(insertedEntries.size(), totalCount, "Entry count doesn't match");
    }

    @Test
    public void processCreateEntityEventTest() throws IOException, InterruptedException {
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, new DbEntry());
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
        EventProcessorHelper.processEvent(entityEvent);
        final var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        final var cache = IocContainer.get(Cache.class);
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        final var totalCount = pageEntries == null
                ? 0
                : pageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
        Assertions.assertEquals(1, totalCount, "Entry count should be 1");
    }

    @Test
    public void processCollectionUsageEventUpsertsUsageEntry() throws IOException, InterruptedException {
        final var mm = IocContainer.get(MemoryManagement.class);
        mm.recordAccess(AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null);
        final var event = new CollectionUsageEvent(AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL, null,
                System.currentTimeMillis());
        EventProcessorHelper.processEvent(event);
        final var cache = IocContainer.get(Cache.class);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry.buildId(TestGlobals.DB, TestGlobals.COLL, "");
        Assertions.assertNotNull(cache.getPkIndexCollectionUsage(id));
    }

    @Test
    public void processCollectionUsageEventIgnoresAdminDb() throws IOException, InterruptedException {
        final var event = new CollectionUsageEvent(AccessKind.COLLECTION, org.techhouse.config.Globals.ADMIN_DB_NAME,
                "databases", null, System.currentTimeMillis());
        EventProcessorHelper.processEvent(event);
        final var cache = IocContainer.get(Cache.class);
        final var id = org.techhouse.data.admin.AdminCollectionUsageEntry
                .buildId(org.techhouse.config.Globals.ADMIN_DB_NAME, "databases", "");
        Assertions.assertNull(cache.getPkIndexCollectionUsage(id));
    }

    @Test
    public void processEntityEventSkipsVanishedCollection() {
        // The collection was dropped while this event was queued (no admin collection entry exists).
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var entry = new DbEntry();
        entry.set_id("ghost");
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "ghost");
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry);

        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(entityEvent));

        // The event is skipped: pending is cleared and no admin page metadata is created for it.
        Assertions.assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("ghost"));
        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL));
    }

}
