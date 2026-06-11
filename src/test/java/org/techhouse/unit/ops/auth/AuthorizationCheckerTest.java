package org.techhouse.unit.ops.auth;

import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.*;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationCheckerTest {
    private AdminUserEntry createAdminUser() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry createNonAdminUser() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    @Test
    public void test_admin_user_allowed_for_every_operation_type() {
        final var admin = createAdminUser();
        final var req = new SaveRequest("testDb", "testColl");
        assertTrue(AuthorizationChecker.check(req, admin).isAllowed());
    }

    @Test
    public void test_non_admin_denied_user_admin_ops() {
        final var user = createNonAdminUser();
        final var createUserReq = new CreateUserRequest();
        assertFalse(AuthorizationChecker.check(createUserReq, user).isAllowed());
    }

    @Test
    public void test_create_database_requires_global_permission_allow() {
        final var perms = new HashSet<GlobalPermissionType>();
        perms.add(GlobalPermissionType.CREATE_DATABASE);
        final var user = new AdminUserEntry("user", "hash", false, perms, new HashMap<>(), new HashMap<>());
        final var req = new CreateDatabaseRequest("newDb");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_create_database_requires_global_permission_deny() {
        final var user = createNonAdminUser();
        final var req = new CreateDatabaseRequest("newDb");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_with_collection_permission() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new FindByIdRequest("testDb", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_falls_back_to_db_permission() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("testDb", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>());
        final var req = new FindByIdRequest("testDb", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_requires_read_write_denied_with_read_only() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_allowed_with_read_write() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_list_databases_always_allowed() {
        final var user = createNonAdminUser();
        final var req = new ListDatabasesRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_close_connection_allowed() {
        final var user = createNonAdminUser();
        final var req = new CloseConnectionRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_missing_all_permissions_denied() {
        final var user = createNonAdminUser();
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_null_user() {
        final var req = new SaveRequest("testDb", "testColl");
        assertFalse(AuthorizationChecker.check(req, null).isAllowed());
    }
}
