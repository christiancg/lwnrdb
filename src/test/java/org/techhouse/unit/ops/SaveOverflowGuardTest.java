package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
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

    private void save(String id, int payloadLength) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, payloadLength));
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private List<Long> pageFileSizes() throws Exception {
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        final var files = folder.listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
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
}
