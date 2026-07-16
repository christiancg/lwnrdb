package org.techhouse.unit.ops;

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

import static org.junit.jupiter.api.Assertions.*;

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

    // schema requires object with a required string "name"
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

    // With no schema installed, any SAVE passes through
    @Test
    public void test_no_schema_passes() {
        assertNull(SchemaValidationHelper.check(save(doc(null))));
    }

    // A compliant SAVE passes
    @Test
    public void test_save_compliant() {
        installSchema();
        assertNull(SchemaValidationHelper.check(save(doc("Alice"))));
    }

    // A non-compliant SAVE is rejected with 400-7
    @Test
    public void test_save_non_compliant() {
        installSchema();
        final var response = SchemaValidationHelper.check(save(doc(null)));
        assertNotNull(response);
        assertEquals("400-7", response.getErrorCode());
    }

    // A SAVE whose field has the wrong type is rejected
    @Test
    public void test_save_wrong_type() {
        installSchema();
        final var obj = new JsonObject();
        obj.add("name", new JsonNumber(5));
        assertNotNull(SchemaValidationHelper.check(save(obj)));
    }

    // BULK_SAVE passes when every document complies
    @Test
    public void test_bulk_all_compliant() {
        installSchema();
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(doc("Alice"), doc("Bob")));
        assertNull(SchemaValidationHelper.check(request));
    }

    // BULK_SAVE is rejected (whole batch) when any document violates, naming the offending id
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

    // Non SAVE/BULK_SAVE requests are ignored by the gate
    @Test
    public void test_other_operation_ignored() {
        assertNull(SchemaValidationHelper.check(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)));
    }

    // The reserved _id field is excluded from validation, so additionalProperties:false still accepts it
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
