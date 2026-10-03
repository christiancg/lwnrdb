package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GrantPrunerTest {
    private static final String BOB = "grantbob";
    private static final String CAROL = "grantcarol";
    private static final String OTHER_DB = "otherdb";

    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        AdminOperationHelper.saveUserEntry(grantedUser(BOB));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry grantedUser(String name) {
        final var databases = new HashMap<String, PermissionLevel>();
        databases.put(TestGlobals.DB, PermissionLevel.READ_WRITE);
        databases.put(OTHER_DB, PermissionLevel.READ);
        final var collections = new HashMap<String, PermissionLevel>();
        collections.put(TestGlobals.DB + "|" + TestGlobals.COLL, PermissionLevel.READ);
        collections.put(TestGlobals.DB + "|" + TestGlobals.JOIN_COLL, PermissionLevel.READ);
        collections.put(OTHER_DB + "|x", PermissionLevel.READ);
        final var scripts = new HashMap<String, ScriptPermissionLevel>();
        scripts.put(TestGlobals.DB, ScriptPermissionLevel.MANAGE);
        scripts.put(OTHER_DB, ScriptPermissionLevel.RUN);
        return new AdminUserEntry(name, "hash", false, new HashSet<>(), databases, collections, scripts);
    }

    private AdminUserEntry bob() {
        return cache.getAdminUserEntry(BOB);
    }

    private static boolean allowedToFind(AdminUserEntry user) {
        return AuthorizationChecker.check(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL), user).isAllowed();
    }

    @Test
    public void test_dropping_a_database_removes_every_grant_naming_it() {
        assertTrue(allowedToFind(bob()));

        final var response = DatabaseOperationHelper
                .processDropDatabaseOperation(new DropDatabaseRequest(TestGlobals.DB));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(Map.of(OTHER_DB, PermissionLevel.READ), bob().getDatabasePermissions());
        assertEquals(Map.of(OTHER_DB + "|x", PermissionLevel.READ), bob().getCollectionPermissions());
        assertEquals(Map.of(OTHER_DB, ScriptPermissionLevel.RUN), bob().getScriptPermissions());
    }

    @Test
    public void test_a_recreated_database_does_not_revive_the_old_grants() throws Exception {
        DatabaseOperationHelper.processDropDatabaseOperation(new DropDatabaseRequest(TestGlobals.DB));
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB, new java.util.ArrayList<>(),
                new java.util.ArrayList<>(List.of("someoneelse"))));
        AdminOperationHelper
                .saveCollectionEntry(new org.techhouse.data.admin.AdminCollEntry(TestGlobals.DB, TestGlobals.COLL));

        assertFalse(allowedToFind(bob()));
        assertFalse(bob().canRunScripts(TestGlobals.DB));
        assertFalse(bob().canManageScripts(TestGlobals.DB));
    }

    @Test
    public void test_a_replicated_database_drop_prunes_too() {
        final var request = new DropDatabaseRequest(TestGlobals.DB);
        request.setReplicated(true);

        DatabaseOperationHelper.processDropDatabaseOperation(request);

        assertFalse(bob().getDatabasePermissions().containsKey(TestGlobals.DB));
    }

    @Test
    public void test_dropping_a_collection_removes_only_its_collection_grant() {
        final var response = CollectionOperationHelper
                .processDropCollectionOperation(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(bob().getCollectionPermissions().containsKey(TestGlobals.DB + "|" + TestGlobals.COLL));
        assertTrue(bob().getCollectionPermissions().containsKey(TestGlobals.DB + "|" + TestGlobals.JOIN_COLL));
        assertEquals(PermissionLevel.READ_WRITE, bob().getDatabasePermissions().get(TestGlobals.DB));
        assertEquals(ScriptPermissionLevel.MANAGE, bob().getScriptPermissions().get(TestGlobals.DB));
    }

    @Test
    public void test_dropping_a_collection_nobody_holds_a_grant_on_changes_no_user() throws Exception {
        TestUtils.createTestJoinCollection();
        final var before = bob();

        CollectionOperationHelper
                .processDropCollectionOperation(new DropCollectionRequest(TestGlobals.DB, TestGlobals.JOIN_COLL));
        CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, "unrelatedcollection"));
        CollectionOperationHelper
                .processDropCollectionOperation(new DropCollectionRequest(TestGlobals.DB, "unrelatedcollection"));

        assertEquals(before.getDatabasePermissions(), bob().getDatabasePermissions());
    }

    private void makeCarolOwnerOfTheTestDatabase() throws Exception {
        AdminOperationHelper.saveUserEntry(grantedUser(CAROL));
        assertTrue(AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(CAROL)));
    }

    @Test
    public void test_deleting_a_user_removes_it_from_the_owners_and_leaves_the_database_ownerless() throws Exception {
        makeCarolOwnerOfTheTestDatabase();
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);

        UserOperationHelper.processDeleteUser(request);

        assertEquals(List.of(), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }

    @Test
    public void test_a_user_created_under_a_deleted_name_does_not_inherit_ownership() throws Exception {
        makeCarolOwnerOfTheTestDatabase();
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);
        UserOperationHelper.processDeleteUser(request);

        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(CAROL, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>()));

        assertFalse(cache.getAdminDbEntry(TestGlobals.DB).isOwner(CAROL));
        final var drop = new org.techhouse.ops.req.DropDatabaseRequest(TestGlobals.DB);
        assertFalse(AuthorizationChecker.check(drop, cache.getAdminUserEntry(CAROL)).isAllowed());
    }

    @Test
    public void test_an_ownerless_database_can_be_given_owners_again() throws Exception {
        makeCarolOwnerOfTheTestDatabase();
        final var delete = new DeleteUserRequest();
        delete.setUsername(CAROL);
        UserOperationHelper.processDeleteUser(delete);
        final var setOwners = new SetDatabaseOwnersRequest(TestGlobals.DB);
        setOwners.setOwners(List.of(BOB));

        final var response = DatabaseOperationHelper.processSetDatabaseOwners(setOwners);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertTrue(cache.getAdminDbEntry(TestGlobals.DB).isOwner(BOB));
    }

    @Test
    public void test_deleting_a_user_keeps_the_other_owners() throws Exception {
        makeCarolOwnerOfTheTestDatabase();
        assertTrue(AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(CAROL, BOB)));
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);

        UserOperationHelper.processDeleteUser(request);

        assertEquals(List.of(BOB), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }
}
