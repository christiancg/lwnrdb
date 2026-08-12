package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessIntegrationTest {
    private static final String ADMIN = "scriptadmin";
    private static final String NOBODY = "scriptnobody";
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        createUser(ADMIN, true);
        createUser(NOBODY, false);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clearSchema() {
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    private static void createUser(String username, boolean admin) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(admin);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("hello"));
        return object;
    }

    private static JsonArray equalsFilterPipeline(String value) {
        final var operator = new JsonObject();
        operator.add("fieldOperatorType", new JsonString("EQUALS"));
        operator.add("field", new JsonString("value"));
        operator.add("value", new JsonString(value));
        final var filterStep = new JsonObject();
        filterStep.add("type", new JsonString("FILTER"));
        filterStep.add("operator", operator);
        final var pipeline = new JsonArray();
        pipeline.add(filterStep);
        return pipeline;
    }

    // An authorized save is committed and can be read back
    @Test
    public void test_authorized_save_and_read() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("u1"));
        final var stored = db.findById(TestGlobals.DB, TestGlobals.COLL, "u1");
        assertNotNull(stored);
        assertEquals("hello", stored.get("value").asJsonString().getValue());
    }

    // findById returns null for a missing document
    @Test
    public void test_find_missing_returns_null() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "does-not-exist"));
    }

    // An unauthorized user's save is denied with a thrown JS error
    @Test
    public void test_unauthorized_save_denied() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        final var error = assertThrows(JsThrowException.class,
                () -> db.save(TestGlobals.DB, TestGlobals.COLL, doc("u2")));
        assertInstanceOf(JsObject.class, error.getValue());
    }

    // A save that violates the collection schema is rejected with a thrown JS error
    @Test
    public void test_schema_violation_rejected() {
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        final var required = new org.techhouse.ejson.elements.JsonArray();
        required.add(new JsonString("mandatory"));
        schema.add("required", required);
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema);

        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class, () -> db.save(TestGlobals.DB, TestGlobals.COLL, doc("u3")));
    }

    // An unknown acting user is rejected
    @Test
    public void test_unknown_user_rejected() {
        final var db = new EnforcingDatabaseAccess("ghost", null);
        assertThrows(JsThrowException.class, db::listDatabases);
    }

    // listCollections and listDatabases return the expected entries for an admin
    @Test
    public void test_list_operations() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertTrue(db.listDatabases().contains(TestGlobals.DB));
        assertTrue(db.listCollections(TestGlobals.DB).contains(TestGlobals.COLL));
    }

    // delete removes a stored document
    @Test
    public void test_delete() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("u4"));
        db.delete(TestGlobals.DB, TestGlobals.COLL, "u4");
        assertNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "u4"));
    }

    // End-to-end: a script importing db saves and returns a value, denied for a user without permission
    @Test
    public void test_script_end_to_end() {
        final var engine = new SimpleJs();
        final var source = "import db from 'db';" + " db.save('" + TestGlobals.DB + "', '" + TestGlobals.COLL
                + "', { _id: 's1', value: 'v' });" + " return db.findById('" + TestGlobals.DB + "', '"
                + TestGlobals.COLL + "', 's1').value;";
        final var allowed = engine.run(source, new SimpleHostBindings(new JsonObject(),
                new EnforcingDatabaseAccess(ADMIN, null), null, ResourceLimits.unlimited()));
        assertFalse(allowed.isError());
        assertEquals("v", allowed.getValue().asJsonString().getValue());

        final var denied = engine.run(source, new SimpleHostBindings(new JsonObject(),
                new EnforcingDatabaseAccess(NOBODY, null), null, ResourceLimits.unlimited()));
        assertTrue(denied.isError());
    }

    // aggregate() returns the matching documents from a FILTER pipeline
    @Test
    public void test_aggregate_returns_results() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var marked = new JsonObject();
        marked.add("_id", new JsonString("agg-match"));
        marked.add("value", new JsonString("unique-aggregate-marker"));
        db.save(TestGlobals.DB, TestGlobals.COLL, marked);

        final var results = db.aggregate(TestGlobals.DB, TestGlobals.COLL,
                equalsFilterPipeline("unique-aggregate-marker"));

        assertEquals(1, results.size());
        assertEquals("agg-match", results.getFirst().get("_id").asJsonString().getValue());
    }

    // aggregate() returns an empty list (not null) when the pipeline matches no documents
    @Test
    public void test_aggregate_returns_empty_list_when_no_results() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var results = db.aggregate(TestGlobals.DB, TestGlobals.COLL,
                equalsFilterPipeline("definitely-does-not-exist-xyz"));
        assertEquals(List.of(), results);
    }

    // save() returns null when the underlying operation is rejected without throwing (an oversized
    // entry, rather than an authorization/schema denial)
    @Test
    public void test_save_oversized_entry_returns_null() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var big = new JsonObject();
        big.add("_id", new JsonString("bigDoc"));
        big.add("bigField", new JsonString("x".repeat(1_048_600)));
        assertNull(db.save(TestGlobals.DB, TestGlobals.COLL, big));
    }

    // listCollections() returns an empty list (not null) for a database that does not exist
    @Test
    public void test_list_collections_for_nonexistent_database_returns_empty() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertEquals(List.of(), db.listCollections("this-db-does-not-exist"));
    }

    // dispatch() routes through processMessage with an explicit (non-null) clientId
    @Test
    public void test_dispatch_with_explicit_client_id() {
        final var db = new EnforcingDatabaseAccess(ADMIN, UUID.randomUUID());
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("u-clientid"));
        final var stored = db.findById(TestGlobals.DB, TestGlobals.COLL, "u-clientid");
        assertNotNull(stored);
        assertEquals("hello", stored.get("value").asJsonString().getValue());
    }
}
