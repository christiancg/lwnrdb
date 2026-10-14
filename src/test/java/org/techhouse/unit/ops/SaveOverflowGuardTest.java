package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SaveOverflowGuardTest {
    private static final long MAX_PAGE_SIZE = 4096L;
    private static final long MAX_ENTRY_SIZE = 1024L;
    private static final int DOCUMENTS = 20;
    private static final int SEEDED = 10;
    private static final int SEED_PAYLOAD = 250;
    private static final int GROWTH = 100;
    private static final int INSERT_PAYLOAD = 600;
    private static final String INSERTED_ID = "fresh";
    private static final int RELOCATION_DOCUMENTS = 5;
    private static final int SMALL = 600;
    private static final int GROWN = 1600;
    private static final String RELOCATED = "r2";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        TestUtils.setPrivateField(configuration, "maxEntrySize", MAX_ENTRY_SIZE);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, int payloadLength) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("payload", new JsonString("x".repeat(payloadLength)));
        return object;
    }

    private OperationStatus saveStatus(String id, int payloadLength) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, payloadLength));
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private void save(String id, int payloadLength) {
        assertEquals(OperationStatus.OK, saveStatus(id, payloadLength));
    }

    private static File collectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
    }

    private List<Long> pageFileSizes() throws Exception {
        final var files = collectionFolder().listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
        final var sizes = new ArrayList<Long>();
        if (files != null) {
            for (final var file : files) {
                sizes.add(Files.size(file.toPath()));
            }
        }
        return sizes;
    }

    private boolean isReadable(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request) instanceof FindByIdResponse;
    }

    @Test
    public void test_back_to_back_in_place_growths_relocate_without_waiting_for_the_indexer() throws Exception {
        for (var i = 0; i < DOCUMENTS; i++) {
            save("g" + i, 20);
        }

        for (var i = 0; i < DOCUMENTS; i++) {
            save("g" + i, 700);
        }

        for (final var size : pageFileSizes()) {
            assertTrue(size <= MAX_PAGE_SIZE, "a page grew to " + size + " bytes, past maxPageSize " + MAX_PAGE_SIZE);
        }
        for (var i = 0; i < DOCUMENTS; i++) {
            assertTrue(isReadable("g" + i), "g" + i + " must survive the relocation");
        }
    }

    @Test
    public void test_the_page_entry_count_is_unchanged_by_an_in_place_update() {
        save("u1", 20);
        final var countAfterInsert = entryCountOfFirstPage();
        final var sizeAfterInsert = sizeOfFirstPage();

        save("u1", 120);

        assertEquals(countAfterInsert, entryCountOfFirstPage(), "an in-place update must not change the row count");
        assertTrue(sizeOfFirstPage() > sizeAfterInsert, "an in-place update must move the recorded page size");
    }

    @Test
    public void test_a_shrinking_update_lowers_the_recorded_page_size() {
        save("s1", 400);
        final var sizeAfterInsert = sizeOfFirstPage();

        save("s1", 20);

        assertTrue(sizeOfFirstPage() < sizeAfterInsert, "a shrinking update must lower the recorded page size");
        assertEquals(1, entryCountOfFirstPage());
    }

    @Test
    public void test_a_bulk_update_still_stays_in_place() throws Exception {
        final var small = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            small.add(document("b" + i, 20));
        }
        bulkSave(small);

        final var grown = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            grown.add(document("b" + i, 700));
        }
        bulkSave(grown);

        assertTrue(pageFileSizes().stream().anyMatch(size -> size > MAX_PAGE_SIZE),
                "a bulk update is documented to stay in place, so it may still overrun a page");
    }

    private void bulkSave(List<JsonObject> objects) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private long sizeOfFirstPage() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().filter(e -> e.getPage() == 0L)
                .mapToLong(AdminPageEntry::getPageSize).findFirst().orElse(0L);
    }

    private int entryCountOfFirstPage() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().filter(e -> e.getPage() == 0L)
                .mapToInt(AdminPageEntry::getEntryCount).findFirst().orElse(0);
    }

    private static String seededId(int index) {
        return "seed" + index;
    }

    private void seedOnePage() {
        for (var i = 0; i < SEEDED; i++) {
            save(seededId(i), SEED_PAYLOAD);
        }
    }

    private List<JsonObject> resizedSeeds(int payloadLength) {
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < SEEDED; i++) {
            objects.add(document(seededId(i), payloadLength));
        }
        return objects;
    }

    private long pageOf(String id) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var position = Collections.binarySearch(primaryKeyIndex, id);
        assertTrue(position >= 0, id + " is not in the PK index");
        return primaryKeyIndex.get(position).getPage();
    }

    private long inMemorySizeOfPageZero() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().filter(p -> p.getPage() == 0)
                .mapToLong(AdminPageEntry::getPageSize).sum();
    }

    private static long fileSizeOfPageZero() throws Exception {
        return Files.size(new File(collectionFolder(),
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + 0L + Globals.DB_FILE_EXTENSION).toPath());
    }

    @Test
    public void test_a_bulk_update_applies_its_size_delta_before_the_insert_half_places_documents() throws Exception {
        seedOnePage();
        assertEquals(0L, pageOf(seededId(0)), "the fixture must seed one page");
        final var objects = resizedSeeds(SEED_PAYLOAD + GROWTH);
        objects.add(document(INSERTED_ID, INSERT_PAYLOAD));

        bulkSave(objects);

        assertNotEquals(0L, pageOf(INSERTED_ID), "the insert was placed against the page's pre-growth size");
    }

    @Test
    public void test_the_next_save_sees_the_grown_page() throws Exception {
        seedOnePage();
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));

        save(INSERTED_ID, INSERT_PAYLOAD);

        assertNotEquals(0L, pageOf(INSERTED_ID));
    }

    @Test
    public void test_a_bulk_update_moves_the_in_memory_size_with_the_file() throws Exception {
        seedOnePage();
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));
        assertEquals(fileSizeOfPageZero(), inMemorySizeOfPageZero(), "after a growth");

        bulkSave(resizedSeeds(1));
        assertEquals(fileSizeOfPageZero(), inMemorySizeOfPageZero(), "after a shrink");
    }

    @Test
    public void test_the_bulk_updated_event_does_not_apply_the_delta_a_second_time() throws Exception {
        seedOnePage();
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));
        final var sizeAfterTheWrite = inMemorySizeOfPageZero();
        final var updated = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL,
                document(seededId(0), SEED_PAYLOAD + GROWTH));
        updated.setPage(0);
        updated.setPreviousByteSize(updated.byteSize() - GROWTH);

        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.UPDATED,
                List.of(EntityRef.of(updated)), 0L);

        assertEquals(sizeAfterTheWrite, inMemorySizeOfPageZero());
    }

    private void seedRelocationPages() throws Exception {
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        for (var i = 0; i < RELOCATION_DOCUMENTS; i++) {
            save("r" + i, SMALL);
        }
    }

    private int payloadLengthOfTheRelocatedDocument() {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(RELOCATED);
        final var response = processor.processMessage(request);
        assertInstanceOf(FindByIdResponse.class, response, RELOCATED + " must stay reachable by id");
        return ((FindByIdResponse) response).getObject().get("payload").asJsonString().getValue().length();
    }

    private static String[] markers() {
        final var found = collectionFolder().list((_, name) -> name.endsWith(".compacting"));
        return found == null ? new String[0] : found;
    }

    @Test
    public void aSuccessfulRelocationLeavesNoMarker() throws Exception {
        seedRelocationPages();

        assertEquals(OperationStatus.OK, saveStatus(RELOCATED, GROWN));

        assertEquals(GROWN, payloadLengthOfTheRelocatedDocument());
        assertEquals(0, markers().length);
    }

    @Test
    public void aFailedInsertRestoresTheOldCopyAndEndsTheMarker() throws Exception {
        seedRelocationPages();
        final var blockedPage = new File(collectionFolder(),
                TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "1" + Globals.DB_FILE_EXTENSION);
        assertTrue(blockedPage.mkdir());
        try {
            assertNotEquals(OperationStatus.OK, saveStatus(RELOCATED, GROWN));

            assertEquals(SMALL, payloadLengthOfTheRelocatedDocument());
            assertEquals(0, markers().length);
        } finally {
            assertTrue(blockedPage.delete());
        }
    }
}
