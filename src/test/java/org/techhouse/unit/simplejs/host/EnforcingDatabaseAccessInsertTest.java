package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessInsertTest {
    private static final String INSERTER = "insertadmin";
    private static final String STRANGER = "insertstranger";
    private final EnforcingDatabaseAccess admin = new EnforcingDatabaseAccess(INSERTER, null);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (final var username : new String[]{INSERTER, STRANGER}) {
            final var request = new CreateUserRequest();
            request.setUsername(username);
            request.setPassword("password123");
            request.setAdmin(INSERTER.equals(username));
            request.setGlobalPermissions(new HashSet<>());
            request.setDatabasePermissions(new HashMap<>());
            request.setCollectionPermissions(new HashMap<>());
            UserOperationHelper.processCreateUser(request);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject named(String id, String name) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("name", new JsonString(name));
        return document;
    }

    private String nameOf(String id) {
        final var document = admin.findById(TestGlobals.DB, TestGlobals.COLL, id);
        assertNotNull(document, id);
        return document.get("name").asJsonString().getValue();
    }

    @Test
    public void test_an_insert_of_a_new_id_returns_the_stored_document() {
        final var stored = admin.insert(TestGlobals.DB, TestGlobals.COLL, named("ins1", "first"));

        assertEquals("ins1", stored.get("_id").asJsonString().getValue());
        assertEquals("first", nameOf("ins1"));
    }

    @Test
    public void test_an_insert_of_an_existing_id_throws_and_changes_nothing() {
        admin.save(TestGlobals.DB, TestGlobals.COLL, named("ins2", "first"));

        final var thrown = assertThrows(JsThrowException.class,
                () -> admin.insert(TestGlobals.DB, TestGlobals.COLL, named("ins2", "second")));

        final var error = assertInstanceOf(JsObject.class, thrown.getValue());
        assertEquals(ErrorCode.DOCUMENT_ALREADY_EXISTS.getDefaultMessage(),
                ((JsString) error.get("message")).getValue());
        assertEquals("first", nameOf("ins2"));
    }

    @Test
    public void test_an_insert_without_a_grant_throws() {
        final var stranger = new EnforcingDatabaseAccess(STRANGER, null);

        assertThrows(JsThrowException.class,
                () -> stranger.insert(TestGlobals.DB, TestGlobals.COLL, named("ins3", "x")));
    }

    @Test
    public void test_a_script_catches_a_refused_insert_and_carries_on() {
        admin.save(TestGlobals.DB, TestGlobals.COLL, named("ins4", "first"));
        final var source = "import db from 'db'; let refused = false; try { db.insert('" + TestGlobals.DB + "', '"
                + TestGlobals.COLL + "', {_id: 'ins4', name: 'second'}); } catch (e) { refused = true; } "
                + "db.insert('" + TestGlobals.DB + "', '" + TestGlobals.COLL + "', {_id: 'ins5', name: 'after'}); "
                + "return refused;";

        final var result = new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), admin, null, ResourceLimits.unlimited()));

        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
        assertEquals("first", nameOf("ins4"));
        assertEquals("after", nameOf("ins5"));
    }
}
