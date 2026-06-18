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
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.IndexEvent;
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
    public void tearDown() throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
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
    public void processIndexEventDeletionTest() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        final var createIndexEvent = new IndexEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "myField");
        EventProcessorHelper.processEvent(createIndexEvent);
        var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        Assertions.assertEquals(1, collEntry.getIndexes().size(), "Index count should be 1");
        final var deleteIndexEvent = new IndexEvent(EventType.DELETED, TestGlobals.DB, TestGlobals.COLL, "myField");
        EventProcessorHelper.processEvent(deleteIndexEvent);
        collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        Assertions.assertEquals(0, collEntry.getIndexes().size(), "Index count should be 0");
    }
}
