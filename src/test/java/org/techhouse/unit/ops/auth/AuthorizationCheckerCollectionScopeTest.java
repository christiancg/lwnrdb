package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.test.TestUtils;

public class AuthorizationCheckerCollectionScopeTest {
    private static final String DB = "testDb";
    private static final String CARVED = "carvedColl";
    private static final String OPEN = "openColl";
    private static final String JOINED = "joinColl";
    private static final String OWNED_DB = "carve_owner_db";

    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static String key(String collName) {
        return DB + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    private AdminUserEntry userWith(Map<String, PermissionLevel> dbPerms, Map<String, PermissionLevel> collPerms) {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, collPerms);
    }

    private AdminUserEntry carvedOutUser() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put(DB, PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        return userWith(dbPerms, collPerms);
    }

    private SaveRequest saveRequest(String collName) {
        final var req = new SaveRequest(DB, collName);
        req.setObject(new JsonObject());
        return req;
    }

    private AggregateRequest aggregateWithSteps(String collName, List<BaseAggregationStep> steps) {
        final var req = new AggregateRequest(DB, collName);
        req.setAggregationSteps(steps);
        return req;
    }

    private List<BaseAggregationStep> joinOnJoinedCollection() {
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new JoinAggregationStep(JOINED, "localField", "remoteField", "asField"));
        return steps;
    }

    private void setOwnedDbInCache() {
        final var entry = new AdminDbEntry(OWNED_DB, new ArrayList<>(), List.of("user"));
        final var pkEntry = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, OWNED_DB,
                0, 10, 0);
        cache.putAdminDbEntry(entry, pkEntry);
    }

    @Test
    public void test_collection_read_narrows_database_read_write_for_save() {
        assertFalse(AuthorizationChecker.check(saveRequest(CARVED), carvedOutUser()).isAllowed());
    }

    @Test
    public void test_collection_read_narrows_database_read_write_for_delete() {
        final var req = new DeleteRequest(DB, CARVED);
        req.set_id("doc1");
        assertFalse(AuthorizationChecker.check(req, carvedOutUser()).isAllowed());
    }

    @Test
    public void test_collection_read_narrows_database_read_write_for_create_index() {
        final var req = new CreateIndexRequest(DB, CARVED, "someField");
        assertFalse(AuthorizationChecker.check(req, carvedOutUser()).isAllowed());
    }

    @Test
    public void test_collection_read_narrows_database_read_write_for_drop_collection() {
        final var req = new DropCollectionRequest(DB, CARVED);
        assertFalse(AuthorizationChecker.check(req, carvedOutUser()).isAllowed());
    }

    @Test
    public void test_collection_read_still_allows_reads_under_a_narrowed_database_grant() {
        final var user = carvedOutUser();
        final var findReq = new FindByIdRequest(DB, CARVED);
        findReq.set_id("doc1");
        assertTrue(AuthorizationChecker.check(findReq, user).isAllowed());
        assertTrue(AuthorizationChecker.check(aggregateWithSteps(CARVED, new ArrayList<>()), user).isAllowed());
    }

    @Test
    public void test_collection_read_write_widens_database_read() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put(DB, PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(OPEN), PermissionLevel.READ_WRITE);
        final var user = userWith(dbPerms, collPerms);
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
        assertFalse(AuthorizationChecker.check(saveRequest(CARVED), user).isAllowed());
    }

    @Test
    public void test_collection_without_an_entry_falls_back_to_the_database_grant() {
        final var user = carvedOutUser();
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
        user.getCollectionPermissions().put("otherDb" + Globals.COLL_IDENTIFIER_SEPARATOR + OPEN, PermissionLevel.READ);
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
    }

    @Test
    public void test_blank_collection_name_resolves_against_the_database_grant() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put(DB, PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        final var user = userWith(dbPerms, collPerms);
        assertTrue(AuthorizationChecker.check(new ListCollectionsRequest(DB), user).isAllowed());
    }

    @Test
    public void test_admin_is_not_narrowed_by_a_collection_entry() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        final var admin = new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), collPerms);
        assertTrue(AuthorizationChecker.check(saveRequest(CARVED), admin).isAllowed());
    }

    @Test
    public void test_owner_is_not_narrowed_by_a_collection_entry() {
        setOwnedDbInCache();
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(OWNED_DB + Globals.COLL_IDENTIFIER_SEPARATOR + CARVED, PermissionLevel.READ);
        final var user = userWith(new HashMap<>(), collPerms);
        final var req = new SaveRequest(OWNED_DB, CARVED);
        req.setObject(new JsonObject());
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_join_collection_read_entry_still_permits_the_join() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put(DB, PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(JOINED), PermissionLevel.READ);
        final var user = userWith(dbPerms, collPerms);
        assertTrue(AuthorizationChecker.check(aggregateWithSteps(OPEN, joinOnJoinedCollection()), user).isAllowed());
    }

    @Test
    public void test_join_collection_entry_that_grants_nothing_denies_the_join() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("otherDb", PermissionLevel.READ_WRITE);
        final var user = userWith(dbPerms, new HashMap<>());
        assertFalse(AuthorizationChecker.check(aggregateWithSteps(OPEN, joinOnJoinedCollection()), user).isAllowed());
    }

    @Test
    public void test_create_collection_is_blocked_by_a_read_entry_for_that_name() {
        final var req = new CreateCollectionRequest(DB, CARVED);
        assertFalse(AuthorizationChecker.check(req, carvedOutUser()).isAllowed());
    }
}
