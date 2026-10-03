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
import org.techhouse.fs.FileSystem;
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
    void reset() {
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
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

    @Test
    public void test_a_failed_schema_read_refuses_the_write() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\": \"obj");

        final var response = SchemaValidationHelper.check(save(doc("Alice")));

        assertNotNull(response, "an unreadable schema must refuse the write, not silently permit it");
        assertEquals("503-11", response.getErrorCode());
    }

    @Test
    public void test_an_unparseable_schema_refuses_the_write() {
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, eJson.fromJson("{\"type\":123}", JsonObject.class));

        final var response = SchemaValidationHelper.check(save(doc("Alice")));

        assertNotNull(response, "a schema that does not meta-validate must refuse the write, not permit every one");
        assertEquals("503-11", response.getErrorCode());
    }

    @Test
    public void test_an_unexpected_failure_refuses_the_write() {
        installSchema();

        final var response = SchemaValidationHelper.check(new SaveRequest(TestGlobals.DB, TestGlobals.COLL));

        assertNotNull(response, "failing open would let an unvalidated document reach the collection");
        assertEquals("503-11", response.getErrorCode());
    }

    @Test
    public void test_a_non_string_id_still_reports_the_schema_failure() {
        installSchema();
        final var bad = new JsonObject();
        bad.add("_id", new JsonNumber(7));
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(bad));

        final var response = SchemaValidationHelper.check(request);

        assertNotNull(response);
        assertEquals("400-7", response.getErrorCode(),
                "building the rejection message must not itself throw on a document whose _id is not a string");
    }

    @Test
    public void test_replacing_the_schema_object_revalidates_against_the_new_one() {
        installSchema();
        assertNull(SchemaValidationHelper.check(save(doc("Alice"))));

        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL,
                eJson.fromJson("{\"type\":\"object\",\"required\":[\"age\"]}", JsonObject.class));

        assertNotNull(SchemaValidationHelper.check(save(doc("Alice"))),
                "the meta-check result is cached per schema instance, so a replacement must not reuse it");
    }

    @Test
    public void test_a_valid_schema_still_validates_normally() {
        installSchema();

        assertNull(SchemaValidationHelper.check(save(doc("Alice"))));
        final var refused = SchemaValidationHelper.check(save(doc(null)));
        assertNotNull(refused);
        assertEquals("400-7", refused.getErrorCode());
    }
}
