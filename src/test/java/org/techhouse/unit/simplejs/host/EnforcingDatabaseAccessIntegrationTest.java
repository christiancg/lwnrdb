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
import org.techhouse.concurrency.ResourceLocking;
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

    // A rejection that is not an authorization/schema denial (here an oversized entry) must still reach
    // the script
    @Test
    public void test_save_oversized_entry_throws() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var big = new JsonObject();
        big.add("_id", new JsonString("bigDoc"));
        big.add("bigField", new JsonString("x".repeat(1_048_600)));
        assertThrows(JsThrowException.class, () -> db.save(TestGlobals.DB, TestGlobals.COLL, big));
    }

    // A database that does not exist is a refused operation, not an empty collection list
    @Test
    public void test_list_collections_for_nonexistent_database_throws() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class, () -> db.listCollections("this-db-does-not-exist"));
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

    private static void assertCollectionLockFree() {
        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "the collection write lock is still held");
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    // bulkSave inserts new ids, updates existing ones and reports both lists
    @Test
    public void test_bulk_save_reports_inserted_and_updated() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("bulk-existing"));
        final var outcome = db.bulkSave(TestGlobals.DB, TestGlobals.COLL,
                List.of(doc("bulk-new"), doc("bulk-existing")));
        assertEquals(List.of("bulk-new"), outcome.inserted());
        assertEquals(List.of("bulk-existing"), outcome.updated());
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "bulk-new"));
    }

    // An oversized document rejects the whole batch rather than reading as "nothing changed"
    @Test
    public void test_bulk_save_oversized_document_is_rejected() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var big = new JsonObject();
        big.add("_id", new JsonString("bulk-big"));
        big.add("bigField", new JsonString("x".repeat(1_048_600)));
        assertThrows(JsThrowException.class, () -> db.bulkSave(TestGlobals.DB, TestGlobals.COLL, List.of(big)));
        assertNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "bulk-big"));
    }

    // A duplicate id inside one batch is rejected
    @Test
    public void test_bulk_save_duplicate_id_in_one_batch_is_rejected() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class,
                () -> db.bulkSave(TestGlobals.DB, TestGlobals.COLL, List.of(doc("bulk-dup"), doc("bulk-dup"))));
    }

    // A user without READ_WRITE is denied before the batch reaches the ops layer
    @Test
    public void test_bulk_save_denied_for_unauthorized_user() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        assertThrows(JsThrowException.class,
                () -> db.bulkSave(TestGlobals.DB, TestGlobals.COLL, List.of(doc("bulk-denied"))));
    }

    // A committed transaction applies every buffered write and releases the collection lock
    @Test
    public void test_transaction_commits_atomically() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("tx-a"));
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("tx-b"));
        db.commitTransaction();
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "tx-a"));
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "tx-b"));
        assertCollectionLockFree();
    }

    // A rolled-back transaction leaves the collection untouched and releases the lock
    @Test
    public void test_transaction_rollback_leaves_no_partial_write() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("tx-rolled-back"));
        db.rollbackTransaction();
        assertNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "tx-rolled-back"));
        assertCollectionLockFree();
    }

    // A non-admin can open a script transaction: the three control ops carry no database to authorize
    @Test
    public void test_transaction_control_ops_skip_authorization() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        db.beginTransaction();
        db.rollbackTransaction();
        assertCollectionLockFree();
    }

    // TransactionOperationHelper's whitelist rejects LIST_COLLECTIONS with 409-6 while a transaction
    // is open. list* keeps the pre-existing swallow-the-error shape (as save/findById do), so a
    // script observes an empty list rather than a throw - documented in docs/simplejs.md.
    @Test
    public void test_list_collections_inside_a_transaction_throws() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        try {
            assertThrows(JsThrowException.class, () -> db.listCollections(TestGlobals.DB));
        } finally {
            db.rollbackTransaction();
        }
        assertCollectionLockFree();
    }

    // Touching an open session from another thread fails loudly instead of stranding the write lock
    @Test
    public void test_cross_thread_use_of_an_open_transaction_throws() throws Exception {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        try {
            final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final var other = new Thread(() -> {
                try {
                    db.save(TestGlobals.DB, TestGlobals.COLL, doc("tx-other-thread"));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            other.start();
            other.join();
            assertInstanceOf(JsThrowException.class, failure.get());
        } finally {
            db.rollbackTransaction();
        }
        assertCollectionLockFree();
    }

    // A second beginTransaction on the same access object is rejected before it reaches START
    @Test
    public void test_nested_begin_transaction_is_rejected() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        db.beginTransaction();
        try {
            assertThrows(JsThrowException.class, db::beginTransaction);
        } finally {
            db.rollbackTransaction();
        }
        assertCollectionLockFree();
    }

    // A save that the server refuses (here: a collection that was never created) must throw rather than
    // return null, which a script cannot distinguish from a successful write
    @Test
    public void test_failed_save_throws_instead_of_returning_null() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class, () -> db.save(TestGlobals.DB, "neverCreated", doc("s1")));
    }

    @Test
    public void test_failed_bulk_save_throws() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class,
                () -> db.bulkSave(TestGlobals.DB, "neverCreated", java.util.List.of(doc("s2"))));
    }

    // Deleting a document that is not there leaves the intended state, so it stays a no-op
    @Test
    public void test_delete_of_absent_document_is_a_no_op() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertDoesNotThrow(() -> db.delete(TestGlobals.DB, TestGlobals.COLL, "never-existed"));
    }

    // ... but a delete the server refuses for any other reason surfaces
    @Test
    public void test_failed_delete_throws() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        assertThrows(JsThrowException.class, () -> db.delete(TestGlobals.DB, TestGlobals.COLL, "u1"));
    }

    @Test
    public void test_failed_aggregate_throws() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        assertThrows(JsThrowException.class,
                () -> db.aggregate(TestGlobals.DB, TestGlobals.COLL, equalsFilterPipeline("hello")));
    }

    @Test
    public void test_failed_find_by_id_throws_but_missing_document_is_null() {
        assertNull(new EnforcingDatabaseAccess(ADMIN, null).findById(TestGlobals.DB, TestGlobals.COLL, "absent"));
        assertThrows(JsThrowException.class,
                () -> new EnforcingDatabaseAccess(NOBODY, null).findById(TestGlobals.DB, TestGlobals.COLL, "u1"));
    }

    @Test
    public void test_failed_list_collections_throws() {
        final var db = new EnforcingDatabaseAccess(NOBODY, null);
        assertThrows(JsThrowException.class, () -> db.listCollections(TestGlobals.DB));
    }
}
