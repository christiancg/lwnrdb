package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DefinitionCacheInvalidationTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject schema() {
        final var schema = new JsonObject();
        schema.addProperty("type", "object");
        return schema;
    }

    @Test
    public void test_a_removal_during_an_in_flight_load_is_not_undone() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, new org.techhouse.ejson.EJson().toJson(schema()));
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));

        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "a delete that answered OK must not keep validating writes against the schema it removed");
    }

    @Test
    public void test_a_reload_after_a_removal_sees_the_new_definition() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, new org.techhouse.ejson.EJson().toJson(schema()));
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "the generation guard must only skip the publish, never turn a present definition into a miss");
    }

    @Test
    public void test_a_miss_is_not_cached_across_a_concurrent_create() throws Exception {
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));

        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, new org.techhouse.ejson.EJson().toJson(schema()));
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "a stale negative entry would report a schema that exists on disk as absent and skip validation");
    }
}
