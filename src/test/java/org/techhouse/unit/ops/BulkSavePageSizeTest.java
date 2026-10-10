package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkSavePageSizeTest {
    private static final long MAX_PAGE_SIZE = 4096L;
    private static final long MAX_ENTRY_SIZE = 1024L;
    private static final int SEEDED = 10;
    private static final int SEED_PAYLOAD = 250;
    private static final int GROWTH = 100;
    private static final int INSERT_PAYLOAD = 600;
    private static final String INSERTED_ID = "fresh";
    private static final Configuration configuration = Configuration.getInstance();

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        TestUtils.setPrivateField(configuration, "maxEntrySize", MAX_ENTRY_SIZE);
        for (var i = 0; i < SEEDED; i++) {
            save(seededId(i), SEED_PAYLOAD);
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String seededId(int index) {
        return "seed" + index;
    }

    private static JsonObject document(String id, int payloadLength) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "x".repeat(payloadLength));
        return object;
    }

    private void save(String id, int payloadLength) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, payloadLength));
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void bulkSave(List<JsonObject> objects) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
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
        return Files
                .size(new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                        TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + 0L + Globals.DB_FILE_EXTENSION).toPath());
    }

    @Test
    public void test_a_bulk_update_applies_its_size_delta_before_the_insert_half_places_documents() throws Exception {
        assertEquals(0L, pageOf(seededId(0)), "the fixture must seed one page");
        final var objects = resizedSeeds(SEED_PAYLOAD + GROWTH);
        objects.add(document(INSERTED_ID, INSERT_PAYLOAD));

        bulkSave(objects);

        assertNotEquals(0L, pageOf(INSERTED_ID), "the insert was placed against the page's pre-growth size");
    }

    @Test
    public void test_the_next_save_sees_the_grown_page() throws Exception {
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));

        save(INSERTED_ID, INSERT_PAYLOAD);

        assertNotEquals(0L, pageOf(INSERTED_ID));
    }

    @Test
    public void test_a_bulk_update_moves_the_in_memory_size_with_the_file() throws Exception {
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));
        assertEquals(fileSizeOfPageZero(), inMemorySizeOfPageZero(), "after a growth");

        bulkSave(resizedSeeds(1));
        assertEquals(fileSizeOfPageZero(), inMemorySizeOfPageZero(), "after a shrink");
    }

    @Test
    public void test_the_bulk_updated_event_does_not_apply_the_delta_a_second_time() throws Exception {
        bulkSave(resizedSeeds(SEED_PAYLOAD + GROWTH));
        final var sizeAfterTheWrite = inMemorySizeOfPageZero();
        final var updated = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL,
                document(seededId(0), SEED_PAYLOAD + GROWTH));
        updated.setPage(0);
        updated.setPreviousByteSize(updated.byteSize() - GROWTH);

        AdminOperationHelper.bulkUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.UPDATED, List.of(updated),
                0L);

        assertEquals(sizeAfterTheWrite, inMemorySizeOfPageZero());
    }
}
