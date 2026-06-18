package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.test.TestUtils;

public class UserOperationHelperAdminTest {
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_delete_non_admin_user_succeeds() {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("nonAdminToDelete");
        createReq.setPassword("password123");
        createReq.setAdmin(false);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());

        UserOperationHelper.processCreateUser(createReq);

        final var deleteReq = new DeleteUserRequest();
        deleteReq.setUsername("nonAdminToDelete");
        final var resp = UserOperationHelper.processDeleteUser(deleteReq);
        assertEquals(OperationStatus.OK, resp.getStatus());
    }

    @Test
    public void test_promote_user_to_admin() {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("topromotee");
        createReq.setPassword("password123");
        createReq.setAdmin(false);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var changeReq = new ChangePermissionsRequest();
        changeReq.setUsername("topromotee");
        changeReq.setAdmin(true);
        changeReq.setGlobalPermissions(new HashSet<>());
        changeReq.setDatabasePermissions(new HashMap<>());
        changeReq.setCollectionPermissions(new HashMap<>());

        final var resp = UserOperationHelper.processChangePermissions(changeReq);
        assertEquals(OperationStatus.OK, resp.getStatus());
        final var user = cache.getAdminUserEntry("topromotee");
        assertTrue(user.isAdmin());
    }

    @Test
    public void test_authenticate_success_sets_client_username() {
        final var password = "correctPassword456";
        final var createReq = new CreateUserRequest();
        createReq.setUsername("authtest");
        createReq.setPassword(password);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var authReq = new AuthenticateRequest();
        authReq.setUsername("authtest");
        authReq.setPassword(password);
        final var clientId = UUID.randomUUID();

        final var resp = UserOperationHelper.processAuthenticate(authReq, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());
    }

    @Test
    public void test_change_permissions_with_permissions() {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("permuser");
        createReq.setPassword("password123");
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.CREATE_DATABASE);
        globalPerms.add(GlobalPermissionType.DROP_DATABASE);

        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("db1", PermissionLevel.READ);
        dbPerms.put("db2", PermissionLevel.READ_WRITE);

        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("db1|coll1", PermissionLevel.READ);
        collPerms.put("db2|coll1", PermissionLevel.READ_WRITE);

        final var changeReq = new ChangePermissionsRequest();
        changeReq.setUsername("permuser");
        changeReq.setAdmin(true);
        changeReq.setGlobalPermissions(globalPerms);
        changeReq.setDatabasePermissions(dbPerms);
        changeReq.setCollectionPermissions(collPerms);

        final var resp = UserOperationHelper.processChangePermissions(changeReq);
        assertEquals(OperationStatus.OK, resp.getStatus());

        final var user = cache.getAdminUserEntry("permuser");
        assertTrue(user.isAdmin());
        assertEquals(globalPerms, user.getGlobalPermissions());
        assertEquals(dbPerms, user.getDatabasePermissions());
        assertEquals(collPerms, user.getCollectionPermissions());
    }

    @Test
    public void test_multiple_users_can_be_created() {
        for (int i = 0; i < 3; i++) {
            final var req = new CreateUserRequest();
            req.setUsername("user" + i);
            req.setPassword("password" + i + "123");
            req.setGlobalPermissions(new HashSet<>());
            req.setDatabasePermissions(new HashMap<>());
            req.setCollectionPermissions(new HashMap<>());
            final var resp = UserOperationHelper.processCreateUser(req);
            assertEquals(OperationStatus.OK, resp.getStatus());
        }

        assertEquals(3, cache.getAllAdminUserEntries().stream().filter(u -> u.get_id().startsWith("user")).count());
    }

    @Test
    public void test_user_password_not_stored_in_plaintext() {
        final var plainPassword = "MySecurePassword789";
        final var req = new CreateUserRequest();
        req.setUsername("secureuser");
        req.setPassword(plainPassword);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);

        final var user = cache.getAdminUserEntry("secureuser");
        assertNotEquals(plainPassword, user.getPasswordHash());
        assertTrue(user.getPasswordHash().contains("pbkdf2"));
    }
}
