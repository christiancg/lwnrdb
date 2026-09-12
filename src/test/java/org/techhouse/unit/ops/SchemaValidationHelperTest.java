package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SchemaValidationHelperTest {
    private final Cache cache = IocContainer.get(Cache.class);
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
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private void installSchema() {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson(
                "{\"type\":\"object\",\"required\":[\"name\"]," + "\"properties\":{\"name\":{\"type\":\"string\"}}}",
                JsonObject.class));
    }

    private SaveRequest save(JsonObject object) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        return request;
    }

    private JsonObject doc(String name) {
        final var obj = new JsonObject();
        if (name != null) {
            obj.add("name", new JsonString(name));
        }
        return obj;
    }

    @Test
    public void test_no_schema_passes() {
        assertNull(SchemaValidationHelper.check(save(doc(null))));
    }

    @Test
    public void test_save_compliant() {
        installSchema();
        assertNull(SchemaValidationHelper.check(save(doc("Alice"))));
    }

    @Test
    public void test_save_non_compliant() {
        installSchema();
        final var response = SchemaValidationHelper.check(save(doc(null)));
        assertNotNull(response);
        assertEquals("400-7", response.getErrorCode());
    }

    @Test
    public void test_save_wrong_type() {
        installSchema();
        final var obj = new JsonObject();
        obj.add("name", new JsonNumber(5));
        assertNotNull(SchemaValidationHelper.check(save(obj)));
    }

    @Test
    public void test_bulk_all_compliant() {
        installSchema();
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(doc("Alice"), doc("Bob")));
        assertNull(SchemaValidationHelper.check(request));
    }

    @Test
    public void test_bulk_one_bad() {
        installSchema();
        final var bad = doc(null);
        bad.add("_id", new JsonString("bad-1"));
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(doc("Alice"), bad));
        final var response = SchemaValidationHelper.check(request);
        assertNotNull(response);
        assertEquals("400-7", response.getErrorCode());
        assertTrue(response.getMessage().contains("bad-1"));
    }

    @Test
    public void test_other_operation_ignored() {
        assertNull(SchemaValidationHelper.check(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_reserved_id_is_excluded_from_validation() {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL,
                eJson.fromJson(
                        "{\"type\":\"object\",\"required\":[\"name\"],"
                                + "\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}",
                        JsonObject.class));
        final var withId = doc("Alice");
        withId.add("_id", new JsonString("alice"));
        assertNull(SchemaValidationHelper.check(save(withId)));

        final var withExtra = doc("Alice");
        withExtra.add("_id", new JsonString("alice"));
        withExtra.add("extra", new JsonString("nope"));
        assertNotNull(SchemaValidationHelper.check(save(withExtra)));
    }
}
