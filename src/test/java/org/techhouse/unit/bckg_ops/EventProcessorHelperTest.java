package org.techhouse.unit.bckg_ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.*;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

public class EventProcessorHelperTest {

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException {
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
        Assertions.assertDoesNotThrow(() -> AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void processProcessEventWithInvalidEventTest() {
        final var event = new Event(EventType.CREATED) {
            @Override
            public EventType getType() {
                return super.getType();
            }
        };
        Assertions.assertThrows(IllegalStateException.class, () -> EventProcessorHelper.processEvent(event));
    }

    @Test
    public void processBulkEntityEventTest() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        final var testObj = new JsonObject();
        testObj.add("myField", "myValue");
        List<DbEntry> insertedEntries = new ArrayList<>(){{ add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, testObj)); }};
        final var bulkEntityEvent = new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL, insertedEntries, new ArrayList<>());
        EventProcessorHelper.processEvent(bulkEntityEvent);
        final var adminCollEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertEquals(adminCollEntry.getEntryCount(), insertedEntries.size(), "Entry count doesn't match");
    }

    @Test
    public void processCreateEntityEventTest() throws IOException, InterruptedException {
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, new DbEntry());
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
        EventProcessorHelper.processEvent(entityEvent);
        final var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        Assertions.assertEquals(collEntry.getEntryCount(), 1, "Entry count should be 1");
    }

    @Test
    public void processIndexEventDeletionTest() throws IOException, InterruptedException {
        TestUtils.createTestDatabaseAndCollection();
        final var createIndexEvent = new IndexEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "myField");
        EventProcessorHelper.processEvent(createIndexEvent);
        var collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        Assertions.assertEquals(collEntry.getIndexes().size(), 1, "Index count should be 1");
        final var deleteIndexEvent = new IndexEvent(EventType.DELETED, TestGlobals.DB, TestGlobals.COLL, "myField");
        EventProcessorHelper.processEvent(deleteIndexEvent);
        collEntry = AdminOperationHelper.getCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        Assertions.assertNotNull(collEntry);
        Assertions.assertEquals(collEntry.getIndexes().size(), 0, "Index count should be 0");
    }
}