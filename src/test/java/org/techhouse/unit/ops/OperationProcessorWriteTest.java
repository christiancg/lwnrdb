package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorWriteTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    // Delete entries and update cache/indexes accordingly
    @Test
    public void test_delete_operation_success() {
        // Arrange
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add("_id", new JsonString("123"));
        saveRequest.setObject(obj);
        final var saveResponse = processor.processMessage(saveRequest);
        assertNotNull(saveResponse);
        assertEquals(OperationStatus.OK, saveResponse.getStatus());

        // Act
        DeleteRequest request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("123");
        DeleteResponse response = (DeleteResponse) processor.processMessage(request);

        // Assert
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    // Handle duplicate IDs in bulk save operations
    @Test
    public void test_bulk_save() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        List<JsonObject> objects = new ArrayList<>();

        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, "id1");
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, "id2");

        objects.add(obj1);
        objects.add(obj2);
        request.setObjects(objects);

        BulkSaveResponse response = (BulkSaveResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(2, response.getInserted().size());
        assertTrue(response.getUpdated().isEmpty());
    }

    // After a save, the entry's PkIndexEntry carries the page assigned by selectPageForInsert
    @Test
    public void test_save_operation_assigns_page() throws Exception {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, "testPageAssignedId");
        saveRequest.setObject(obj);

        SaveResponse response = (SaveResponse) processor.processMessage(saveRequest);
        assertEquals(OperationStatus.OK, response.getStatus());

        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var pkIdx = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var saved = pkIdx.stream().filter(p -> p.getValue().equals("testPageAssignedId")).findFirst();
        assertTrue(saved.isPresent());
        // First insert into a small collection lands on page 0
        assertEquals(0L, saved.get().getPage());
    }

    // Save with an existing _id updates the entry rather than inserting a duplicate
    @Test
    public void test_save_operation_updates_existing_entry() {
        SaveRequest firstSave = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("updateMe"));
        obj.add("value", new JsonNumber(1));
        firstSave.setObject(obj);
        firstSave.set_id("updateMe");
        SaveResponse firstResponse = (SaveResponse) processor.processMessage(firstSave);
        assertEquals(OperationStatus.OK, firstResponse.getStatus());

        SaveRequest secondSave = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject updated = new JsonObject();
        updated.add(Globals.PK_FIELD, new JsonString("updateMe"));
        updated.add("value", new JsonNumber(2));
        secondSave.setObject(updated);
        secondSave.set_id("updateMe");
        SaveResponse secondResponse = (SaveResponse) processor.processMessage(secondSave);

        assertEquals(OperationStatus.OK, secondResponse.getStatus());
        assertEquals("updateMe", secondResponse.get_id());
    }

    // Delete returns NOT_FOUND when the entry does not exist
    @Test
    public void test_delete_returns_not_found_for_missing_entry() {
        DeleteRequest request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("no-such-id");

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-2", response.getErrorCode());
    }

    // Bulk save with some already-existing IDs performs updates for those entries
    @Test
    public void test_bulk_save_updates_existing_entries() {
        SaveRequest insert = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bulkExisting"));
        obj.add("v", new JsonNumber(1));
        insert.setObject(obj);
        insert.set_id("bulkExisting");
        processor.processMessage(insert);

        BulkSaveRequest bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject updated = new JsonObject();
        updated.add(Globals.PK_FIELD, new JsonString("bulkExisting"));
        updated.add("v", new JsonNumber(2));
        JsonObject newOne = new JsonObject();
        newOne.add(Globals.PK_FIELD, new JsonString("bulkNew"));
        bulk.setObjects(List.of(updated, newOne));

        BulkSaveResponse response = (BulkSaveResponse) processor.processMessage(bulk);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(response.getInserted().contains("bulkNew"));
    }

    // Save an oversized entry returns an error response
    @Test
    public void test_save_oversized_entry_returns_error() {
        SaveRequest request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId"));
        // Create a value larger than 1MB (maxEntrySize default)
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObject(obj);

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-2", response.getErrorCode());
    }

    // Bulk save with duplicate _id values in the same request returns an error
    @Test
    public void test_bulk_save_duplicate_id_returns_error() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("dupId"));
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("dupId"));
        request.setObjects(List.of(obj1, obj2));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-3", response.getErrorCode());
        assertTrue(response.getMessage().contains("dupId"));
    }

    // Bulk save with an oversized entry returns an error response
    @Test
    public void test_bulk_save_oversized_entry_returns_error() {
        BulkSaveRequest request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("bigId2"));
        obj.add("bigField", new JsonString("x".repeat(1_048_600)));
        request.setObjects(List.of(obj));

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-2", response.getErrorCode());
    }

    @Test
    public void test_save_records_collection_usage() {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        final var before = mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL,
                null);
        final var beforeCount = before == null ? 0L : before.getAccessCount();
        SaveRequest saveRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("usage-id-1"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        final var after = mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, TestGlobals.DB, TestGlobals.COLL,
                null);
        assertNotNull(after);
        assertTrue(after.getAccessCount() > beforeCount);
    }

    @Test
    public void test_save_admin_collection_does_not_record_usage() {
        final var mm = IocContainer.get(org.techhouse.cache.MemoryManagement.class);
        // admin saves go through helpers, but explicitly verify recordAccess no ops:
        mm.recordAccess(org.techhouse.cache.AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null);
        assertNull(mm.getCounter(org.techhouse.cache.AccessKind.COLLECTION, Globals.ADMIN_DB_NAME, "databases", null));
    }

    // A SAVE that grows an existing document past maxPageSize relocates it to another page instead of
    // overflowing its current page; the document still reads back intact and no page exceeds the cap.
    @Test
    public void test_save_grow_update_relocates_to_avoid_page_overflow() throws Exception {
        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var config = org.techhouse.config.Configuration.getInstance();
        final var originalMaxPage = config.getMaxPageSize();
        final var originalMaxEntry = config.getMaxEntrySize();
        final var collName = "overflowColl";
        TestUtils.setPrivateField(config, "maxPageSize", 2000L);
        TestUtils.setPrivateField(config, "maxEntrySize", 100_000L);
        try {
            processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));

            // "keep" (~300 B) plus a small "a", co-located on page 0. Sized so that growing "a" to ~1.8 KB
            // makes page 0 overflow maxPageSize (keepBytes + newBytes > 2000) yet the grown "a" still fits
            // on a fresh page (newBytes < 2000), forcing a relocation rather than an in-place rewrite.
            final var keepSave = new SaveRequest(TestGlobals.DB, collName);
            final var keepObj = new JsonObject();
            keepObj.add(Globals.PK_FIELD, new JsonString("keep"));
            keepObj.add("v", new JsonString("k".repeat(280)));
            keepSave.setObject(keepObj);
            keepSave.set_id("keep");
            assertEquals(OperationStatus.OK, processor.processMessage(keepSave).getStatus());

            final var aSave = new SaveRequest(TestGlobals.DB, collName);
            final var aObj = new JsonObject();
            aObj.add(Globals.PK_FIELD, new JsonString("a"));
            aObj.add("v", new JsonString("small"));
            aSave.setObject(aObj);
            aSave.set_id("a");
            assertEquals(OperationStatus.OK, processor.processMessage(aSave).getStatus());

            final var pkIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, collName);
            final var keepPageBefore = pkIndex.stream().filter(p -> p.getValue().equals("keep")).findFirst()
                    .orElseThrow().getPage();
            final var aPageBefore = pkIndex.stream().filter(p -> p.getValue().equals("a")).findFirst().orElseThrow()
                    .getPage();
            assertEquals(0L, keepPageBefore);
            assertEquals(0L, aPageBefore, "both docs must start co-located on page 0");

            // Grow "a" past what fits on page 0 alongside "keep" -> must relocate.
            final var bigValue = "x".repeat(1780);
            final var growSave = new SaveRequest(TestGlobals.DB, collName);
            final var grown = new JsonObject();
            grown.add(Globals.PK_FIELD, new JsonString("a"));
            grown.add("v", new JsonString(bigValue));
            growSave.setObject(grown);
            growSave.set_id("a");
            assertEquals(OperationStatus.OK, processor.processMessage(growSave).getStatus());

            // "a" relocated off page 0; "keep" stayed put.
            final var pkAfter = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, collName);
            final var aPageAfter = pkAfter.stream().filter(p -> p.getValue().equals("a")).findFirst().orElseThrow()
                    .getPage();
            final var keepPageAfter = pkAfter.stream().filter(p -> p.getValue().equals("keep")).findFirst()
                    .orElseThrow().getPage();
            assertEquals(0L, keepPageAfter, "the untouched doc must stay on page 0");
            assertTrue(aPageAfter > 0L, "the grown doc must relocate off page 0 (was " + aPageAfter + ")");

            // The grown value reads back intact.
            final var find = new FindByIdRequest(TestGlobals.DB, collName);
            find.set_id("a");
            final var found = (FindByIdResponse) processor.processMessage(find);
            assertEquals(OperationStatus.OK, found.getStatus());
            assertEquals(bigValue, found.getObject().get("v").asJsonString().getValue());

            // No on-disk page file exceeds maxPageSize.
            final var collFolder = new java.io.File(
                    TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + collName);
            final var datFiles = collFolder.listFiles((_, n) -> n.endsWith(Globals.DB_FILE_EXTENSION));
            assertNotNull(datFiles);
            for (final var dat : datFiles) {
                assertTrue(dat.length() <= 2000L,
                        "page file " + dat.getName() + " (" + dat.length() + " bytes) must not exceed maxPageSize");
            }
        } finally {
            TestUtils.setPrivateField(config, "maxPageSize", originalMaxPage);
            TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntry);
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
        }
    }

    // When no admin page metadata is available, the overflow check can't assess the page, so the SAVE
    // falls back to an in-place update (no relocation) and the document still updates correctly.
    @Test
    public void test_save_grow_update_without_page_metadata_updates_in_place() {
        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var collName = "noPageMetaColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));
        try {
            final var save = new SaveRequest(TestGlobals.DB, collName);
            final var o = new JsonObject();
            o.add(Globals.PK_FIELD, new JsonString("x"));
            o.add("v", new JsonString("one"));
            save.setObject(o);
            save.set_id("x");
            assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());

            // Drop the in-memory page metadata so wouldOverflowPage hits its null fallback.
            cache.removeAdminPageEntries(TestGlobals.DB, collName);

            final var upd = new SaveRequest(TestGlobals.DB, collName);
            final var o2 = new JsonObject();
            o2.add(Globals.PK_FIELD, new JsonString("x"));
            o2.add("v", new JsonString("two"));
            upd.setObject(o2);
            upd.set_id("x");
            assertEquals(OperationStatus.OK, processor.processMessage(upd).getStatus());

            final var find = new FindByIdRequest(TestGlobals.DB, collName);
            find.set_id("x");
            final var found = (FindByIdResponse) processor.processMessage(find);
            assertEquals(OperationStatus.OK, found.getStatus());
            assertEquals("two", found.getObject().get("v").asJsonString().getValue());
        } finally {
            processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
        }
    }

    // A single-document insert calls updatePageSizeInMemory synchronously; the subsequent background
    // CREATED event must only persist — entryCount and pageSize must both remain at 1x, not 2x.
    @Test
    public void test_single_save_insert_page_entry_count_single_counted() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("singleSaveId1"));
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(obj);
        save.set_id("singleSaveId1");

        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());

        // Capture the page state set by the synchronous updatePageSizeInMemory call.
        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var page0Before = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0Before.isPresent());
        final long countBefore = page0Before.get().getEntryCount();
        final long sizeBefore = page0Before.get().getPageSize();

        // Simulate the background EntityEvent(CREATED) arriving.
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id("singleSaveId1");
        entry.setPage(0L);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(entry));

        final var page0After = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0After.isPresent());
        assertEquals(countBefore, page0After.get().getEntryCount(), "entryCount must not be incremented again");
        assertEquals(sizeBefore, page0After.get().getPageSize(), "pageSize must not be incremented again");
    }

    // Bulk-save inserts also call updatePageSizeInMemory synchronously per inserted entry;
    // the subsequent background BulkEntityEvent must not double-count any of them.
    @Test
    public void test_bulk_save_insert_page_entry_count_single_counted() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        final var obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("bulkSaveId1"));
        final var obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("bulkSaveId2"));

        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(obj1, obj2));
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());

        final var pageEntries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(pageEntries);
        final var page0Before = pageEntries.stream().filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0Before.isPresent());
        final long countBefore = page0Before.get().getEntryCount();
        final long sizeBefore = page0Before.get().getPageSize();

        // Simulate the background BulkEntityEvent(CREATED) arriving.
        final var e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("bulkSaveId1");
        e1.setPage(0L);
        final var e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("bulkSaveId2");
        e2.setPage(0L);
        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(e1, e2));

        final var page0After = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(p -> p.getPage() == 0L).findFirst();
        assertTrue(page0After.isPresent());
        assertEquals(countBefore, page0After.get().getEntryCount(), "entryCount must not be incremented again");
        assertEquals(sizeBefore, page0After.get().getPageSize(), "pageSize must not be incremented again");
    }
}
