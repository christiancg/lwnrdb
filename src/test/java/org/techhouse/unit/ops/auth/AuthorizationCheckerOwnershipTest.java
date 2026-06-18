package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.test.TestUtils;

public class AuthorizationCheckerOwnershipTest {
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private AdminUserEntry nonAdminWithDropPerm() {
        final var perms = new HashSet<GlobalPermissionType>();
        perms.add(GlobalPermissionType.DROP_DATABASE);
        return new AdminUserEntry("user", "hash", false, perms, new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry nonAdminNoPerms() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private void setOwnerInCache(String dbName) {
        final var entry = new AdminDbEntry(dbName, new ArrayList<>(), List.of("user"));
        final var pkEntry = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, dbName, 0,
                10, 0);
        cache.putAdminDbEntry(entry, pkEntry);
    }

    @Test
    public void test_drop_database_requires_ownership_not_just_global_perm() {
        final var user = nonAdminWithDropPerm();
        final var req = new DropDatabaseRequest("somedb");
        // user has DROP_DATABASE global perm but is not owner → FORBIDDEN
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_database_allowed_for_owner() {
        setOwnerInCache("owneddb");
        final var user = nonAdminNoPerms();
        final var req = new DropDatabaseRequest("owneddb");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_owner_can_do_all_ops_on_owned_db() {
        setOwnerInCache("ownerdb");
        final var user = nonAdminNoPerms();

        final var saveReq = new SaveRequest("ownerdb", "somecoll");
        assertTrue(AuthorizationChecker.check(saveReq, user).isAllowed());

        final var findReq = new FindByIdRequest("ownerdb", "somecoll");
        assertTrue(AuthorizationChecker.check(findReq, user).isAllowed());

        final var dropCollReq = new DropCollectionRequest("ownerdb", "somecoll");
        assertTrue(AuthorizationChecker.check(dropCollReq, user).isAllowed());
    }

    @Test
    public void test_owner_of_one_db_cannot_access_another() {
        setOwnerInCache("db1");
        final var user = nonAdminNoPerms();
        final var req = new SaveRequest("db2", "coll");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_set_database_owners_forbidden_for_non_admin() {
        final var user = nonAdminNoPerms();
        final var req = new SetDatabaseOwnersRequest("somedb");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_database_no_entry_in_cache_forbidden() {
        final var user = nonAdminWithDropPerm();
        final var req = new DropDatabaseRequest("nonexistentdb");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_database_db_exists_but_user_not_owner_forbidden() {
        setOwnerInCache("otherownersdb");
        // "user2" is NOT in the owners list (only "user" is)
        final var user2 = new AdminUserEntry("user2", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
        final var req = new DropDatabaseRequest("otherownersdb");
        assertFalse(AuthorizationChecker.check(req, user2).isAllowed());
    }

    @Test
    public void test_list_users_forbidden_for_non_admin() {
        final var user = nonAdminNoPerms();
        final var req = new org.techhouse.ops.req.ListUsersRequest();
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }
}
