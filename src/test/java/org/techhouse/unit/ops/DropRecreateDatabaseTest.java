package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DropRecreateDatabaseTest {
    private static final String STRAY_COLL = "strayCollection";
    private static final String STRAY_DB = "strayDatabase";
    private static final String DELETE_COLLECTION_ENTRY = "deleteCollectionEntry";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        final var strayDatabase = databaseFolder(STRAY_DB);
        if (strayDatabase.isDirectory()) {
            TestUtils.deleteFolder(strayDatabase);
        }
        pending.clearDatabase(TestGlobals.DB);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void recreateCollection() {
        processor.processMessage(new CreateDatabaseRequest(TestGlobals.DB));
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
    }

    private void save(String value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("shared"));
        obj.addProperty("value", value);
        request.setObject(obj);
        request.set_id("shared");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void count() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        processor.processMessage(request);
    }

    private String valueOf() {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("shared");
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertInstanceOf(FindByIdResponse.class, response);
        return ((FindByIdResponse) response).getObject().get("value").asJsonString().getValue();
    }

    private static File collectionFolder(String dbName, String collName) {
        return new File(new File(TestGlobals.PATH, dbName), collName);
    }

    private static File databaseFolder(String dbName) {
        return new File(TestGlobals.PATH, dbName);
    }

    private static File createStrayFolder(File folder) {
        assertTrue(folder.mkdirs(), "the fixture could not create " + folder);
        return folder;
    }

    private OperationStatus dropCollection(String dbName, String collName) {
        return processor.processMessage(new DropCollectionRequest(dbName, collName)).getStatus();
    }

    private OperationStatus dropDatabase(String dbName) {
        return processor.processMessage(new DropDatabaseRequest(dbName)).getStatus();
    }

    @Test
    public void test_recreated_collection_does_not_inherit_the_dropped_pk_index() {
        save("before");
        count();
        // The state evictDatabase used to leave behind: the pk index is cached with no documents
        // beside it, which is what memory pressure and every index-only read produce.
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateDatabaseRequest(TestGlobals.DB)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        save("after");

        assertEquals("after", valueOf());
    }

    @Test
    public void test_case_differing_database_is_refused() {
        final var colliding = processor
                .processMessage(new CreateDatabaseRequest(TestGlobals.DB.toUpperCase(Locale.ROOT)));
        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), colliding.getErrorCode());
    }

    @Test
    public void shouldLeaveAnUnregisteredCollectionFolderOnDisk() {
        final var stray = createStrayFolder(collectionFolder(TestGlobals.DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldLeaveAnUnregisteredDatabaseFolderOnDisk() {
        final var stray = createStrayFolder(databaseFolder(STRAY_DB));
        assertEquals(OperationStatus.ERROR, dropDatabase(STRAY_DB));
        assertTrue(stray.isDirectory(), "an unregistered database folder must not be deleted");
    }

    @Test
    public void shouldLeaveACollectionFolderOnDiskWhenItsDatabaseIsNotRegistered() {
        final var stray = createStrayFolder(collectionFolder(STRAY_DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(STRAY_DB, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldStillDropARegisteredCollection() {
        final var registered = collectionFolder(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(registered.exists(), "a registered collection must still be removed");
    }

    @Test
    public void shouldStillDropARegisteredDatabase() {
        final var registered = databaseFolder(TestGlobals.DB);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropDatabase(TestGlobals.DB));
        assertFalse(registered.exists(), "a registered database must still be removed");
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameCollection() {
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameDatabase() {
        assertEquals(OperationStatus.OK, dropDatabase(TestGlobals.DB));
        assertEquals(OperationStatus.ERROR, dropDatabase(TestGlobals.DB));
    }

    @Test
    public void shouldLeaveTheRegisteredSiblingIntactWhenAStrayFolderIsDropped() {
        createStrayFolder(collectionFolder(TestGlobals.DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, STRAY_COLL));
        assertTrue(collectionFolder(TestGlobals.DB, TestGlobals.COLL).isDirectory(),
                "dropping an unregistered name must not touch a registered collection");
    }

    @Test
    public void drop_collection_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(collectionFolder(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL),
                "the collection must no longer be registered");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
    }

    @Test
    public void drop_database_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(databaseFolder(TestGlobals.DB));

        assertEquals(OperationStatus.OK, dropDatabase(TestGlobals.DB));
        assertNull(cache.getAdminDbEntry(TestGlobals.DB), "the database must no longer be registered");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateDatabaseRequest(TestGlobals.DB)).getStatus());
    }

    @Test
    public void drop_collection_after_a_failed_admin_write_can_be_retried() {
        final var failuresLeft = new AtomicInteger(1);
        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (DELETE_COLLECTION_ENTRY.equals(invocation.getMethod().getName())
                    && failuresLeft.getAndDecrement() > 0) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, TestGlobals.COLL));
            assertFalse(collectionFolder(TestGlobals.DB, TestGlobals.COLL).exists(),
                    "the first attempt deleted the folder before failing");
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL),
                    "the failed attempt left the collection registered");

            assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
            assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        }
    }

    @Test
    public void test_a_dropped_collection_leaves_no_pending_ids_for_its_next_life() throws IOException {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "old_life");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "new_life");
        assertTrue(TestUtils.dirtyIndexCollections().contains(TestGlobals.DB + "|" + TestGlobals.COLL),
                "the re-created collection's first pending write must write its own dirty marker");
    }

    @Test
    public void test_a_dropped_database_leaves_no_pending_ids_for_its_next_life() throws IOException {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "old_life");

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());

        recreateCollection();
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "new_life");
        assertEquals(List.of("new_life"), List.copyOf(pending.idsFor(TestGlobals.DB, TestGlobals.COLL)));
        assertTrue(TestUtils.dirtyIndexCollections().contains(TestGlobals.DB + "|" + TestGlobals.COLL));
    }
}
