package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropySchemaTest {
    private static final String SECOND_COLL = "conformSecond";
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"name\"]}";

    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, SECOND_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, SECOND_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, SECOND_COLL));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        for (final var collName : new String[]{TestGlobals.COLL, SECOND_COLL}) {
            fs.deleteCollectionSchema(TestGlobals.DB, collName);
            cache.removeCollectionSchema(TestGlobals.DB, collName);
        }
    }

    private static void conform(AdminAntiEntropyService target, AdminSnapshotPayload snapshot) throws Exception {
        final var conformerField = AdminAntiEntropyService.class.getDeclaredField("conformer");
        conformerField.setAccessible(true);
        final var conformer = conformerField.get(target);
        final var method = conformer.getClass().getDeclaredMethod("conform", AdminSnapshotPayload.class);
        method.setAccessible(true);
        method.invoke(conformer, snapshot);
    }

    private void writeSchema(String collName) throws Exception {
        cache.putCollectionSchema(TestGlobals.DB, collName, eJson.fromJson(SCHEMA, JsonObject.class));
        fs.writeCollectionSchema(TestGlobals.DB, collName, SCHEMA);
    }

    private void tearTheSchemaFile() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\": \"obj");
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_an_unreadable_schema_is_rewritten_from_the_snapshot() throws Exception {
        writeSchema(TestGlobals.COLL);
        final var snapshot = service.buildSnapshot();
        tearTheSchemaFile();

        conform(service, snapshot);

        assertNotNull(cache.loadSchemaUncached(TestGlobals.DB, TestGlobals.COLL),
                "the mechanism that repairs the file must not be the one that aborts on it");
    }

    @Test
    public void test_an_unreadable_schema_does_not_abort_the_round() throws Exception {
        writeSchema(TestGlobals.COLL);
        writeSchema(SECOND_COLL);
        final var snapshot = service.buildSnapshot();
        tearTheSchemaFile();
        fs.deleteCollectionSchema(TestGlobals.DB, SECOND_COLL);
        cache.removeCollectionSchema(TestGlobals.DB, SECOND_COLL);

        assertDoesNotThrow(() -> conform(service, snapshot));

        assertNotNull(cache.loadSchemaUncached(TestGlobals.DB, SECOND_COLL),
                "every collection after the unreadable one used to be skipped for the life of the process");
    }

    @Test
    public void test_a_snapshot_is_still_served_when_one_schema_cannot_be_read() throws Exception {
        writeSchema(TestGlobals.COLL);
        writeSchema(SECOND_COLL);
        tearTheSchemaFile();

        final var snapshot = assertDoesNotThrow(service::buildSnapshot);

        assertFalse(snapshot.getSchemas().has(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)),
                "the file that cannot be read is left out");
        assertTrue(snapshot.getSchemas().has(Cache.getCollectionIdentifier(TestGlobals.DB, SECOND_COLL)),
                "a node that can never serve ADMIN_SNAPSHOT is ignored by every peer indefinitely");
    }

    @Test
    public void test_a_readable_schema_still_conforms_to_the_snapshot() throws Exception {
        writeSchema(TestGlobals.COLL);
        final var snapshot = service.buildSnapshot();
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        conform(service, snapshot);

        assertEquals(eJson.fromJson(SCHEMA, JsonObject.class),
                cache.loadSchemaUncached(TestGlobals.DB, TestGlobals.COLL));
    }
}
