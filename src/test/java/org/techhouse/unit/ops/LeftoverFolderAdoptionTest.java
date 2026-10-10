package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.admin.LeftoverFolders;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LeftoverFolderAdoptionTest {
    private static final String COLLECTION = "leftover_coll";
    private static final String DATABASE = "leftover_db";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File[] quarantinedIn(File parent, String prefix) {
        final var found = parent.listFiles((_, name) -> name.startsWith(prefix + Globals.QUARANTINE_INFIX));
        assertNotNull(found);
        return found;
    }

    private static File testDatabaseFolder() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB);
    }

    private void createCollection(String db) {
        final var response = CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(db, COLLECTION));
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    private void save(String id) {
        final var save = new SaveRequest(TestGlobals.DB, COLLECTION);
        final var doc = new JsonObject();
        doc.add(Globals.PK_FIELD, new JsonString(id));
        save.setObject(doc);
        save.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void leaveAPopulatedUnregisteredCollection() throws Exception {
        createCollection(TestGlobals.DB);
        save("predrop");
        cache.evictCollection(TestGlobals.DB, COLLECTION);
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, COLLECTION);
        AdminOperationHelper.deletePageCollections(TestGlobals.DB, COLLECTION);
    }

    @Test
    public void test_a_schema_loaded_just_before_a_leftover_move_does_not_outlive_it() throws Exception {
        leaveAPopulatedUnregisteredCollection();
        fs.writeCollectionSchema(TestGlobals.DB, COLLECTION, "{\"type\":\"object\",\"required\":[\"legacy\"]}");
        final var original = fs.folderQuarantine();
        final var loadingFirst = Mockito.spy(original);
        Mockito.doAnswer(invocation -> {
            cache.getCollectionSchema(TestGlobals.DB, COLLECTION);
            return invocation.callRealMethod();
        }).when(loadingFirst).moveCollectionAside(TestGlobals.DB, COLLECTION, 0L);
        TestUtils.setPrivateField(fs, "folderQuarantine", loadingFirst);
        try {
            assertTrue(LeftoverFolders.moveAsideUnregisteredCollection(TestGlobals.DB, COLLECTION));
        } finally {
            TestUtils.setPrivateField(fs, "folderQuarantine", original);
        }

        assertNull(cache.getCollectionSchema(TestGlobals.DB, COLLECTION),
                "the leftover folder's schema is gone, so the next incarnation must not be validated against it");
    }

    @Test
    public void test_create_collection_moves_aside_a_populated_unregistered_folder() throws Exception {
        leaveAPopulatedUnregisteredCollection();

        createCollection(TestGlobals.DB);

        assertTrue(cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, COLLECTION).isEmpty(),
                "a re-created collection must not serve the documents of the incarnation it replaces");
        assertEquals(1, quarantinedIn(testDatabaseFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_adopts_an_empty_unregistered_folder() throws Exception {
        fs.createCollectionFile(TestGlobals.DB, COLLECTION);

        createCollection(TestGlobals.DB);

        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, COLLECTION));
        assertEquals(0, quarantinedIn(testDatabaseFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_on_a_registered_name_keeps_its_documents() throws Exception {
        createCollection(TestGlobals.DB);
        save("kept");

        createCollection(TestGlobals.DB);

        assertEquals(1, cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, COLLECTION).size());
        assertEquals(0, quarantinedIn(testDatabaseFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_refuses_a_leftover_it_cannot_move() throws Exception {
        leaveAPopulatedUnregisteredCollection();
        final var dbDir = testDatabaseFolder();
        assertTrue(dbDir.setWritable(false));
        try {
            final var response = CollectionOperationHelper
                    .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, COLLECTION));

            assertEquals(ErrorCode.ERROR_CREATING_COLLECTION.getCode(), response.getErrorCode());
        } finally {
            assertTrue(dbDir.setWritable(true));
        }
    }

    @Test
    public void test_create_database_moves_aside_a_populated_unregistered_folder() throws Exception {
        assertEquals(OperationStatus.OK, DatabaseOperationHelper
                .processCreateDatabaseOperation(new CreateDatabaseRequest(DATABASE), null).getStatus());
        createCollection(DATABASE);
        fs.writeProcedure(DATABASE, "oldproc", "{\"name\":\"oldproc\"}");
        cache.evictDatabase(DATABASE);
        AdminOperationHelper.deleteDatabaseEntry(DATABASE);

        final var response = DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(DATABASE),
                null);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(fs.listProcedureNames(DATABASE).isEmpty(), "a dropped database's procedures must not come back");
        assertTrue(cache.getCollectionNamesForDatabase(DATABASE).isEmpty());
        assertEquals(1, quarantinedIn(new File(TestGlobals.PATH), DATABASE).length);
    }

    @Test
    public void test_create_database_refuses_a_leftover_it_cannot_move() throws Exception {
        assertTrue(fs.createDatabaseFolder(DATABASE));
        fs.writeProcedure(DATABASE, "oldproc", "{\"name\":\"oldproc\"}");
        final var root = new File(TestGlobals.PATH);
        assertTrue(root.setWritable(false));
        try {
            final var response = DatabaseOperationHelper
                    .processCreateDatabaseOperation(new CreateDatabaseRequest(DATABASE), null);

            assertEquals(ErrorCode.ERROR_CREATING_DATABASE.getCode(), response.getErrorCode());
        } finally {
            assertTrue(root.setWritable(true));
        }
    }
}
