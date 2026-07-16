package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AdminCache;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminCacheSchemaTest {
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() {
        adminCache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private JsonObject schema(String type) {
        final var s = new JsonObject();
        s.add("type", new JsonString(type));
        return s;
    }

    // A collection with no schema file returns null (and negatively caches the absence)
    @Test
    public void test_absent_schema_returns_null() {
        assertNull(adminCache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // put makes the schema visible without touching disk
    @Test
    public void test_put_then_get() {
        final var s = schema("object");
        adminCache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, s);
        assertEquals(s, adminCache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // a schema written to disk is lazily loaded on first access
    @Test
    public void test_lazy_load_from_disk() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, eJson.toJson(schema("string")));
        assertEquals(schema("string"), adminCache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // remove clears the cached schema
    @Test
    public void test_remove() {
        adminCache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema("object"));
        adminCache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        assertNull(adminCache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // removeCollectionSchemasForDatabase clears every collection schema under the database
    @Test
    public void test_remove_for_database() {
        adminCache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema("object"));
        adminCache.removeCollectionSchemasForDatabase(TestGlobals.DB);
        assertNull(adminCache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }
}
