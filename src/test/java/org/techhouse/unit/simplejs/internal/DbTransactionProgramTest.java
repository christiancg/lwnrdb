package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DbTransactionProgramTest {
    private static final String ADMIN = "txscriptadmin";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static ScriptResult run(String source) {
        return run(source, ResourceLimits.unlimited());
    }

    private static ScriptResult run(String source, ResourceLimits limits) {
        return new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), new EnforcingDatabaseAccess(ADMIN, null), null, limits));
    }

    private static JsonObject read(String id) {
        return new EnforcingDatabaseAccess(ADMIN, null).findById(TestGlobals.DB, TestGlobals.COLL, id);
    }

    private static void seed(String id, String value) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("value", new JsonString(value));
        new EnforcingDatabaseAccess(ADMIN, null).save(TestGlobals.DB, TestGlobals.COLL, document);
    }

    private static void assertCollectionLockFree() {
        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "the collection write lock is still held");
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    private static String script(String body) {
        return "import db from 'db';\nconst DB = '" + TestGlobals.DB + "';\nconst C = '" + TestGlobals.COLL + "';\n"
                + body;
    }

    // A read-modify-write across two documents commits as one unit
    @Test
    public void test_read_modify_write_commits() {
        seed("prog-a", "one");
        seed("prog-b", "two");
        final var result = run(script("""
                return db.transaction(() => {
                    const a = db.findById(DB, C, 'prog-a');
                    const b = db.findById(DB, C, 'prog-b');
                    db.save(DB, C, { _id: 'prog-a', value: a.value + '+' + b.value });
                    db.save(DB, C, { _id: 'prog-b', value: 'done' });
                    return 'ok';
                });
                """));
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("ok", result.getValue().asJsonString().getValue());
        assertEquals("one+two", read("prog-a").get("value").asJsonString().getValue());
        assertEquals("done", read("prog-b").get("value").asJsonString().getValue());
        assertCollectionLockFree();
    }

    // A callback that throws after a write leaves no partial write behind
    @Test
    public void test_throwing_callback_leaves_no_partial_write() {
        final var result = run(script("""
                db.transaction(() => {
                    db.save(DB, C, { _id: 'prog-partial', value: 'x' });
                    throw new Error('boom');
                });
                """));
        assertTrue(result.isError());
        assertNull(read("prog-partial"));
        assertCollectionLockFree();
    }

    // The single most important case: a sandbox abort is not catchable by user code and skips
    // `finally`, but the Java-level rollback in DbModule still runs, so the locks are released.
    @Test
    public void test_instruction_budget_exceeded_inside_the_callback_rolls_back() {
        final var result = run(script("""
                db.transaction(() => {
                    db.save(DB, C, { _id: 'prog-aborted', value: 'x' });
                    while (true) { }
                });
                """), new ResourceLimits(50_000, -1, -1, false));
        assertTrue(result.isError());
        assertEquals("ScriptLimitError", result.getErrorName());
        assertNull(read("prog-aborted"));
        assertCollectionLockFree();
    }

    // bulkSave inside a transaction commits the whole batch
    @Test
    public void test_bulk_save_inside_a_transaction() {
        final var result = run(script("""
                return db.transaction(() => db.bulkSave(DB, C, [
                    { _id: 'prog-bulk-1', value: 'a' },
                    { _id: 'prog-bulk-2', value: 'b' }
                ]).inserted.length);
                """));
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals(2, result.getValue().asJsonNumber().asInteger());
        assertNotNull(read("prog-bulk-1"));
        assertNotNull(read("prog-bulk-2"));
        assertCollectionLockFree();
    }

    // A geo/vector field read back from the database keeps its type on disk after a script rewrite
    @Test
    public void test_custom_type_round_trips_through_a_script() {
        final var result = run(script("""
                db.save(DB, C, { _id: 'prog-geo', at: new Geo(41.5, -3.25), v: new Vector([1, 2, 3]) });
                const stored = db.findById(DB, C, 'prog-geo');
                db.save(DB, C, { _id: 'prog-geo', at: new Geo(stored.at.lat + 1, stored.at.lng), v: stored.v });
                return String(db.findById(DB, C, 'prog-geo').at);
                """));
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("#geo(42.5,-3.25)", result.getValue().asJsonString().getValue());
        final var stored = read("prog-geo");
        assertEquals("#geo(42.5,-3.25)", stored.get("at").asJsonString().getValue());
        assertEquals("#vector(1.0,2.0,3.0)", stored.get("v").asJsonString().getValue());
    }

    // A Geo nested inside an array inside an object keeps its type all the way to disk
    @Test
    public void test_nested_custom_type_in_a_saved_document() {
        final var result = run(script("""
                db.save(DB, C, { _id: 'prog-nested', route: { stops: [new Geo(1, 2), new Geo(3, 4)] } });
                return String(db.findById(DB, C, 'prog-nested').route.stops[1]);
                """));
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("#geo(3.0,4.0)", result.getValue().asJsonString().getValue());
        assertEquals("#geo(1.0,2.0)", read("prog-nested").get("route").asJsonObject().get("stops").asJsonArray().get(0)
                .asJsonString().getValue());
    }

    // Two script transactions contending for the same collection do not deadlock: the second waits
    // out transactionLockTimeoutMs and rolls back rather than blocking forever.
    @Test
    public void test_concurrent_transactions_on_one_collection_do_not_deadlock() throws Exception {
        final var second = new java.util.concurrent.atomic.AtomicReference<ScriptResult>();
        final var firstHoldsLock = new java.util.concurrent.CountDownLatch(1);
        final var secondFinished = new java.util.concurrent.CountDownLatch(1);
        final var access = new EnforcingDatabaseAccess(ADMIN, null);
        access.beginTransaction();
        try {
            final var document = new JsonObject();
            document.add("_id", new JsonString("prog-contended"));
            document.add("value", new JsonString("first"));
            access.save(TestGlobals.DB, TestGlobals.COLL, document);
            firstHoldsLock.countDown();
            final var contender = new Thread(() -> {
                second.set(run(script("""
                        db.transaction(() => db.save(DB, C, { _id: 'prog-contender', value: 'second' }));
                        """)));
                secondFinished.countDown();
            });
            contender.start();
            assertTrue(secondFinished.await(30, java.util.concurrent.TimeUnit.SECONDS), "the contender deadlocked");
            contender.join();
        } finally {
            access.rollbackTransaction();
        }
        assertTrue(second.get().isError());
        assertNull(read("prog-contender"));
        assertCollectionLockFree();
    }

    // The checklist's regression guard: the geo/vector aggregation operators must work against
    // documents written *through a script* rather than the wire. This is what would break if
    // EJsonInterop emitted a plain string instead of a real JsonGeo/JsonVector — the documents would
    // still read back fine, but the type-specific operators would no longer match them.
    @Test
    public void test_geo_and_vector_operators_match_script_written_documents() {
        final var result = run(script("""
                db.bulkSave(DB, C, [
                    { _id: 'op-close', location: new Geo(40.001, -74.001), embedding: new Vector([1, 0, 0]) },
                    { _id: 'op-far', location: new Geo(10, 10), embedding: new Vector([0, 0, 1]) }
                ]);
                const near = db.aggregate(DB, C, [{
                    type: 'FILTER',
                    operator: {
                        customOperatorName: 'distance',
                        field: 'location',
                        value: new Geo(40, -74),
                        comparator: 'SMALLER_THAN',
                        distance: 1000
                    }
                }]);
                const inside = db.aggregate(DB, C, [{
                    type: 'FILTER',
                    operator: {
                        customOperatorName: 'within',
                        field: 'location',
                        value: new Geo(40, -74),
                        polygon: [new Geo(39, -75), new Geo(41, -75), new Geo(41, -73), new Geo(39, -73)]
                    }
                }]);
                const closest = db.aggregate(DB, C, [{
                    type: 'FILTER',
                    operator: {
                        customOperatorName: 'nearest',
                        field: 'embedding',
                        value: new Vector([1, 0, 0]),
                        k: 1
                    }
                }]);
                return [near.map(d => d._id).join(','), inside.map(d => d._id).join(','),
                        closest.map(d => d._id).join(',')].join('|');
                """));
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("op-close|op-close|op-close", result.getValue().asJsonString().getValue());
    }
}
