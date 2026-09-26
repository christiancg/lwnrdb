package org.techhouse.unit.bckg_ops;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
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
    public void processProcessEventWithInvalidEventTest() {
        final var event = new Event(EventType.CREATED) {
        };
        Assertions.assertThrows(IllegalStateException.class, () -> EventProcessorHelper.processEvent(event));
    }

    @Test
    public void processScriptRunHistoryEventTest() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", true);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryKinds", "TRIGGER");
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryMaxErrorChars", 2000);
        ScriptRunHistory.reset();
        final var record = new ScriptRunRecord("evt-run-1", ScriptRunKind.TRIGGER, TestGlobals.DB, "job", "proc",
                TestGlobals.COLL, "CREATED", "u1", "u2", System.currentTimeMillis(), 3L, 1, ScriptRunRecord.OUTCOME_OK,
                null, null, null, null, null, false);

        EventProcessorHelper.processEvent(new ScriptRunHistoryEvent(record));

        Assertions.assertEquals(1L, ScriptRunHistory.getRecorded());
        final var stored = IocContainer.get(Cache.class)
                .getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME).get("evt-run-1");
        Assertions.assertNotNull(stored);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", false);
        ScriptRunHistory.reset();
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
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
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
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var entry = new DbEntry();
        entry.set_id("ghost");
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "ghost");
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry);

        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(entityEvent));

        Assertions.assertFalse(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("ghost"));
        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void processEntityEvent_no_orphan_pages_when_collection_dropped_mid_flight()
            throws IOException, InterruptedException {
        // Register the collection so the early guard in processEntityEvent passes...
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
        // ...then simulate the concurrent drop completing before baseUpdateEntryCount runs.
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        AdminOperationHelper.deletePageCollections(TestGlobals.DB, TestGlobals.COLL);

        final var entry = new DbEntry();
        entry.set_id("mid-flight-entity");
        final var entityEvent = new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry);
        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(entityEvent));

        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL),
                "No orphan page metadata must be created for a dropped collection");
    }

    @Test
    public void processBulkEntityEvent_no_orphan_pages_when_collection_dropped_mid_flight()
            throws IOException, InterruptedException {
        // Register the collection so the early guard in processBulkEntityEvent passes...
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));
        // ...then simulate the concurrent drop completing before baseUpdateEntryCount runs.
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        AdminOperationHelper.deletePageCollections(TestGlobals.DB, TestGlobals.COLL);

        final var entry = new DbEntry();
        entry.set_id("mid-flight-bulk");
        final var bulkEvent = new BulkEntityEvent(TestGlobals.DB, TestGlobals.COLL, new ArrayList<>(List.of(entry)),
                new ArrayList<>());
        Assertions.assertDoesNotThrow(() -> EventProcessorHelper.processEvent(bulkEvent));

        final var cache = IocContainer.get(Cache.class);
        Assertions.assertNull(cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL),
                "No orphan page metadata must be created for a dropped collection");
    }

    private static DbEntry indexedEntry(String collName, String id, int value) {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString(id));
        data.addProperty("f", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, data);
        entry.set_id(id);
        return entry;
    }

    private static List<Event> cacheAndBuild(String collName, List<String> ids) {
        final var cache = IocContainer.get(Cache.class);
        final var events = new ArrayList<Event>();
        var value = 1;
        for (final var id : ids) {
            final var entry = indexedEntry(collName, id, value++);
            cache.addEntryToCache(TestGlobals.DB, collName, entry);
            events.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, collName, entry));
        }
        return events;
    }

    private static void indexField(String collName) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, collName, "f");
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, collName).setIndexes(Set.of("f"));
    }

    private void runBatchWithAFailingFirstGroup() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        final var batch = new ArrayList<>(cacheAndBuild(TestGlobals.COLL, List.of("a1", "a2")));
        indexField(TestGlobals.COLL);
        indexField(TestGlobals.JOIN_COLL);
        batch.addAll(cacheAndBuild(TestGlobals.JOIN_COLL, List.of("b1", "b2")));

        final var fs = IocContainer.get(FileSystem.class);
        TestUtils.deleteFolder(new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + TestGlobals.COLL));

        EventProcessorHelper.processBatch(batch);
    }

    @Test
    public void test_one_failing_group_does_not_skip_the_rest() throws Exception {
        runBatchWithAFailingFirstGroup();

        final var index = IocContainer.get(Cache.class).getFieldIndexAndLoadIfNecessary(TestGlobals.DB,
                TestGlobals.JOIN_COLL, "f", Number.class);
        Assertions.assertNotNull(index, "the second group must still have been indexed");
        final var indexed = index.stream().flatMap(e -> e.getIds().stream()).collect(Collectors.toSet());
        Assertions.assertEquals(Set.of("b1", "b2"), indexed,
                "one transient failure must not abort the rest of the batch");
    }

    @Test
    public void test_a_failing_group_still_clears_its_pending_ids() throws Exception {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, List.of("a1", "a2"));

        runBatchWithAFailingFirstGroup();

        Assertions.assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "a group that failed must still clear its overlay entries, or the overlay grows without bound");
    }
}
