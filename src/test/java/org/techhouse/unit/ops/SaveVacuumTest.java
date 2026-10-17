package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SaveVacuumTest {
    private static final long MAX_PAGE_SIZE = 4096L;
    private static final int DOCUMENTS = 6;
    private static final int PAYLOAD = 280;
    private static final int UPDATES = 40;
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String marker) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("marker", new JsonString(marker));
        object.add("payload", new JsonString("x".repeat(PAYLOAD)));
        return object;
    }

    private void save(String id, String marker) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, marker));
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private String markerOf(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        final var response = assertInstanceOf(FindByIdResponse.class, processor.processMessage(request));
        return response.getObject().get("marker").asJsonString().getValue();
    }

    private static File[] pageFiles() {
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        return folder.listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
    }

    private void assertTheCacheAgreesWithTheFiles() throws Exception {
        final var onDisk = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .map(PkIndexEntry::toFileEntry).sorted().toList();
        final var resident = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .map(PkIndexEntry::toFileEntry).sorted().toList();
        assertEquals(onDisk, resident, "every vacuum must reach the resident pk rows");
        for (final var page : cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL)) {
            final var live = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                    .filter(row -> row.getPage() == page.getPage()).mapToLong(PkIndexEntry::getLength).sum();
            assertEquals(live, page.getPageSize(), "page " + page.getPage() + " occupancy is its live bytes");
        }
    }

    @Test
    public void updateChurnVacuumsThePageInsteadOfGrowingOrRelocating() throws Exception {
        for (var i = 0; i < DOCUMENTS; i++) {
            save("d" + i, "v0");
        }
        for (var round = 1; round <= UPDATES; round++) {
            save("d" + round % DOCUMENTS, "v" + round);
        }

        for (var i = 0; i < DOCUMENTS; i++) {
            assertEquals("v" + (UPDATES - Math.floorMod(UPDATES - i, DOCUMENTS)), markerOf("d" + i));
        }
        assertEquals(1, pageFiles().length, "a page with a quarter free after a vacuum keeps its documents");
        assertTrue(pageFiles()[0].length() <= MAX_PAGE_SIZE + MAX_PAGE_SIZE / Globals.PAGE_VACUUM_DEAD_DIVISOR);
        assertTheCacheAgreesWithTheFiles();
    }

    @Test
    public void bulkUpdateChurnKeepsTheCacheOnTheMovedRecords() throws Exception {
        for (var i = 0; i < DOCUMENTS; i++) {
            save("d" + i, "v0");
        }
        for (var round = 1; round <= UPDATES / DOCUMENTS; round++) {
            final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            final var objects = new java.util.ArrayList<JsonObject>();
            for (var i = 0; i < DOCUMENTS; i += 2) {
                objects.add(document("d" + i, "bulk" + round));
            }
            bulk.setObjects(objects);
            assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        }

        for (final var id : List.of("d0", "d2", "d4")) {
            assertEquals("bulk" + UPDATES / DOCUMENTS, markerOf(id));
        }
        assertEquals("v0", markerOf("d1"));
        assertTheCacheAgreesWithTheFiles();
    }

    @Test
    public void aSecondCacheInstanceNeverTakesTheVacuumsOfTheContainersCache() throws Exception {
        final var throwaway = new Cache();
        TestUtils.setPrivateField(throwaway, "userCache",
                org.mockito.Mockito.mock(org.techhouse.cache.UserCache.class));
        TestUtils.setPrivateField(throwaway, "adminCache",
                org.mockito.Mockito.mock(org.techhouse.cache.AdminCache.class));
        for (var i = 0; i < DOCUMENTS; i++) {
            save("d" + i, "v0");
        }
        for (var round = 1; round <= UPDATES; round++) {
            save("d" + round % DOCUMENTS, "v" + round);
        }

        assertTheCacheAgreesWithTheFiles();
    }
}
