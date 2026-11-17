package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.SchemaBudget;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DroppedCollections;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SchemaValidationHelperIdTest {
    private static final String OTHER_COLL = "otherColl";
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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
    void reset() throws Exception {
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.metadataFiles().deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private JsonObject json(String text) {
        return eJson.fromJson(text, JsonObject.class);
    }

    private String errorsFor(String schema, String document) {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json(schema));
        return SchemaValidationHelper.schemaErrors(TestGlobals.DB, TestGlobals.COLL, json(document),
                new SchemaBudget(1_000_000));
    }

    private static Map<?, ?> checkedSchemas() throws Exception {
        return TestUtils.getPrivateStaticField(SchemaValidationHelper.class, "checkedSchemas", Map.class);
    }

    private static String key(String collName) {
        return TestGlobals.DB + "|" + collName;
    }

    @Test
    public void test_the_id_stays_invisible_to_every_object_keyword() {
        assertNull(errorsFor("{\"additionalProperties\":false,\"properties\":{\"name\":{}}}",
                "{\"_id\":\"a\",\"name\":\"x\"}"));
        assertEquals("<root>: missing required property '_id'",
                errorsFor("{\"required\":[\"_id\"]}", "{\"_id\":\"a\",\"name\":\"x\"}"));
        assertNull(errorsFor("{\"propertyNames\":{\"maxLength\":2}}", "{\"_id\":\"a\",\"ab\":1}"));
        assertEquals("<root>: object has more than 1 properties",
                errorsFor("{\"maxProperties\":1}", "{\"_id\":\"a\",\"x\":1,\"y\":2}"));
        assertNull(errorsFor("{\"maxProperties\":1}", "{\"_id\":\"a\",\"x\":1}"));
        assertNull(errorsFor("{\"const\":{\"name\":\"x\"}}", "{\"_id\":\"a\",\"name\":\"x\"}"));
        assertEquals("/nested: missing required property 'name'",
                errorsFor("{\"properties\":{\"nested\":{\"required\":[\"_id\",\"name\"]}}}",
                        "{\"_id\":\"a\",\"nested\":{\"_id\":\"b\"}}"));
    }

    @Test
    public void test_validation_never_alters_the_document() {
        final var document = json("{\"_id\":\"a\",\"name\":\"x\"}");
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json("{\"required\":[\"_id\"]}"));
        SchemaValidationHelper.schemaErrors(TestGlobals.DB, TestGlobals.COLL, document, new SchemaBudget(1_000));
        assertEquals(json("{\"_id\":\"a\",\"name\":\"x\"}"), document);
    }

    @Test
    public void test_dropping_a_collection_or_database_forgets_its_checked_schema() throws Exception {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json("{\"type\":\"object\"}"));
        cache.putCollectionSchema(TestGlobals.DB, OTHER_COLL, json("{\"type\":\"object\"}"));
        assertNull(SchemaValidationHelper.schemaErrors(TestGlobals.DB, TestGlobals.COLL, json("{}"),
                new SchemaBudget(1_000)));
        assertNull(
                SchemaValidationHelper.schemaErrors(TestGlobals.DB, OTHER_COLL, json("{}"), new SchemaBudget(1_000)));
        assertTrue(checkedSchemas().containsKey(key(TestGlobals.COLL)));
        assertTrue(checkedSchemas().containsKey(key(OTHER_COLL)));
        DroppedCollections.forgetCollection(TestGlobals.DB, OTHER_COLL);
        assertFalse(checkedSchemas().containsKey(key(OTHER_COLL)));
        assertTrue(checkedSchemas().containsKey(key(TestGlobals.COLL)));
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json("{\"type\":\"object\"}"));
        SchemaValidationHelper.schemaErrors(TestGlobals.DB, TestGlobals.COLL, json("{}"), new SchemaBudget(1_000));
        DroppedCollections.forgetDatabase(TestGlobals.DB);
        for (final var key : checkedSchemas().keySet()) {
            assertFalse(key.toString().startsWith(TestGlobals.DB + "|"), "kept " + key);
        }
    }
}
