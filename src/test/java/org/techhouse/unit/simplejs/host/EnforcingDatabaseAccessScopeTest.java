package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessScopeTest {
    private static final String ADMIN = "scopedadmin";

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

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("scoped"));
        return object;
    }

    private static EnforcingDatabaseAccess scoped() {
        return new EnforcingDatabaseAccess(ADMIN, null, TestGlobals.DB);
    }

    @Test
    public void test_scoped_database_is_reported() {
        assertEquals(TestGlobals.DB, scoped().scopedDatabase());
        assertNull(new EnforcingDatabaseAccess(ADMIN, null).scopedDatabase());
    }

    @Test
    public void test_access_within_the_scope_is_allowed() {
        final var db = scoped();
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("in-scope"));
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "in-scope"));
    }

    @Test
    public void test_access_to_another_database_throws() {
        final var db = scoped();
        final var error = assertThrows(JsThrowException.class, () -> db.findById("otherDb", "someColl", "x"));
        assertNotNull(error.getValue());
    }

    // Even an admin acting user cannot reach the admin database through a scoped script
    @Test
    public void test_access_to_admin_database_throws() {
        final var db = scoped();
        assertThrows(JsThrowException.class,
                () -> db.findById(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, ADMIN));
    }

    @Test
    public void test_write_outside_the_scope_throws() {
        final var db = scoped();
        assertThrows(JsThrowException.class, () -> db.save("otherDb", TestGlobals.COLL, doc("nope")));
        assertThrows(JsThrowException.class, () -> db.delete("otherDb", TestGlobals.COLL, "nope"));
        assertThrows(JsThrowException.class, () -> db.listCollections("otherDb"));
    }

    @Test
    public void test_list_databases_returns_only_the_scope() {
        assertEquals(java.util.List.of(TestGlobals.DB), scoped().listDatabases());
    }

    @Test
    public void test_unscoped_instance_is_unrestricted() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertTrue(db.listDatabases().contains(TestGlobals.DB));
        assertNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "absent"));
    }

    // Transaction control carries no database, so the scope check must not reject it
    @Test
    public void test_transaction_within_the_scope_commits() {
        final var db = scoped();
        db.beginTransaction();
        db.save(TestGlobals.DB, TestGlobals.COLL, doc("tx-scoped"));
        db.commitTransaction();
        assertNotNull(db.findById(TestGlobals.DB, TestGlobals.COLL, "tx-scoped"));
    }
}
