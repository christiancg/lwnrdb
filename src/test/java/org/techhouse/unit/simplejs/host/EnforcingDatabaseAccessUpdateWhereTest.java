package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessUpdateWhereTest {
    private static final String UPDATER = "updatewhereadmin";
    private static final String STRANGER = "updatewherestranger";
    private final EnforcingDatabaseAccess admin = new EnforcingDatabaseAccess(UPDATER, null);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (final var username : new String[]{UPDATER, STRANGER}) {
            final var request = new CreateUserRequest();
            request.setUsername(username);
            request.setPassword("password123");
            request.setAdmin(UPDATER.equals(username));
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

    private void seed(String id, String tag) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("tag", new JsonString(tag));
        document.add("kept", new JsonString("k-" + id));
        admin.save(TestGlobals.DB, TestGlobals.COLL, document);
    }

    private static JsonObject tagIs(String tag) {
        final var condition = new JsonObject();
        condition.add("fieldOperatorType", new JsonString("EQUALS"));
        condition.add("field", new JsonString("tag"));
        condition.add("value", new JsonString(tag));
        return condition;
    }

    private static JsonObject stateTo(String state) {
        final var patch = new JsonObject();
        patch.add("state", new JsonString(state));
        return patch;
    }

    private String field(String id, String name) {
        final var document = admin.findById(TestGlobals.DB, TestGlobals.COLL, id);
        assertNotNull(document, id);
        return document.has(name) ? document.get(name).asJsonString().getValue() : null;
    }

    @Test
    public void test_update_where_merges_into_every_match_and_returns_the_count() {
        seed("uw1", "bump");
        seed("uw2", "bump");
        seed("uw3", "stay");

        assertEquals(2L, admin.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("bump"), stateTo("bumped")));

        assertEquals("bumped", field("uw1", "state"));
        assertEquals("k-uw1", field("uw1", "kept"));
        assertEquals("bumped", field("uw2", "state"));
        assertNull(field("uw3", "state"));
    }

    @Test
    public void test_update_where_of_nothing_returns_zero() {
        assertEquals(0L, admin.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("absent"), stateTo("x")));
    }

    @Test
    public void test_update_where_past_the_cap_throws_and_changes_nothing() throws Exception {
        final var configuration = Configuration.getInstance();
        seed("ucap1", "capped");
        seed("ucap2", "capped");
        TestUtils.setPrivateField(configuration, "updateMaxMatches", 1);
        try {
            assertThrows(JsThrowException.class,
                    () -> admin.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("capped"), stateTo("x")));
        } finally {
            TestUtils.setPrivateField(configuration, "updateMaxMatches", ConfigKey.UPDATE_MAX_MATCHES.defaultValue());
        }
        assertNull(field("ucap1", "state"));
    }

    @Test
    public void test_update_where_with_an_id_in_the_patch_throws() {
        final var patch = stateTo("x");
        patch.add("_id", new JsonString("renamed"));

        assertThrows(JsThrowException.class,
                () -> admin.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("bump"), patch));
    }

    @Test
    public void test_update_where_passes_a_schema_requiring_fields_the_patch_lacks() {
        final var processor = IocContainer.get(OperationProcessor.class);
        seed("ureq1", "required");
        assertEquals(OperationStatus.OK,
                processor.processMessage(RequestParser.parseRequest("{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\""
                        + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                        + "\",\"schema\":{\"type\":\"object\",\"required\":[\"tag\",\"kept\"]}}")).getStatus());
        try {
            assertEquals(1L,
                    admin.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("required"), stateTo("validated")));
        } finally {
            processor.processMessage(RequestParser.parseRequest("{\"type\":\"DELETE_SCHEMA\",\"databaseName\":\""
                    + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL + "\"}"));
        }
        assertEquals("validated", field("ureq1", "state"));
    }

    @Test
    public void test_update_where_without_a_grant_throws() {
        final var stranger = new EnforcingDatabaseAccess(STRANGER, null);

        assertThrows(JsThrowException.class,
                () -> stranger.updateWhere(TestGlobals.DB, TestGlobals.COLL, tagIs("bump"), stateTo("x")));
    }

    @Test
    public void test_a_script_updates_where_and_reads_the_count() {
        seed("ujs1", "from-script");
        final var source = "import db from 'db'; return db.updateWhere('" + TestGlobals.DB + "', '" + TestGlobals.COLL
                + "', {fieldOperatorType: 'EQUALS', field: 'tag', value: 'from-script'}, {state: 'scripted'});";

        final var result = new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), admin, null, ResourceLimits.unlimited()));

        assertFalse(result.isError());
        assertEquals(1.0, result.getValue().asJsonNumber().getValue().doubleValue());
        assertEquals("scripted", field("ujs1", "state"));
    }
}
