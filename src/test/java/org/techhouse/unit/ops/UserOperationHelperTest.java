package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.*;
import org.techhouse.test.TestUtils;

public class UserOperationHelperTest {
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
    public void test_authenticate_unknown_user_returns_error() {
        final var req = new AuthenticateRequest();
        req.setUsername("unknownuser");
        req.setPassword("password");
        final var resp = UserOperationHelper.processAuthenticate(req, UUID.randomUUID());
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("doesn't exist or the wrong credentials"));
    }

    @Test
    public void test_authenticate_wrong_password_returns_error() {
        final var password = "correctPassword123";
        final var createReq = new CreateUserRequest();
        createReq.setUsername("testuser");
        createReq.setPassword(password);
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var authReq = new AuthenticateRequest();
        authReq.setUsername("testuser");
        authReq.setPassword("wrongPassword");
        final var resp = UserOperationHelper.processAuthenticate(authReq, UUID.randomUUID());
        assertEquals(OperationStatus.ERROR, resp.getStatus());
    }

    @Test
    public void test_create_user_persists_and_caches() {
        final var req = new CreateUserRequest();
        req.setUsername("newuser");
        req.setPassword("password123");
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());

        final var resp = UserOperationHelper.processCreateUser(req);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNotNull(cache.getAdminUserEntry("newuser"));
    }

    @Test
    public void test_create_user_existing_returns_error() {
        final var req = new CreateUserRequest();
        req.setUsername("duplicate");
        req.setPassword("password123");
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());

        UserOperationHelper.processCreateUser(req);
        final var resp2 = UserOperationHelper.processCreateUser(req);
        assertEquals(OperationStatus.ERROR, resp2.getStatus());
        assertTrue(resp2.getMessage().contains("already exists"));
    }

    @Test
    public void test_delete_user_removes_from_cache_and_storage() {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("userToDelete");
        createReq.setPassword("password123");
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        assertNotNull(cache.getAdminUserEntry("userToDelete"));

        final var deleteReq = new DeleteUserRequest();
        deleteReq.setUsername("userToDelete");
        final var resp = UserOperationHelper.processDeleteUser(deleteReq);
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertNull(cache.getAdminUserEntry("userToDelete"));
    }

    @Test
    public void test_delete_user_not_found() {
        final var req = new DeleteUserRequest();
        req.setUsername("nonexistent");
        final var resp = UserOperationHelper.processDeleteUser(req);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }

    @Test
    public void test_change_permissions_updates_existing() {
        final var createReq = new CreateUserRequest();
        createReq.setUsername("permChangeUser");
        createReq.setPassword("password123");
        createReq.setGlobalPermissions(new HashSet<>());
        createReq.setDatabasePermissions(new HashMap<>());
        createReq.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(createReq);

        final var changeReq = new ChangePermissionsRequest();
        changeReq.setUsername("permChangeUser");
        changeReq.setAdmin(true);
        changeReq.setGlobalPermissions(new HashSet<>());
        changeReq.setDatabasePermissions(new HashMap<>());
        changeReq.setCollectionPermissions(new HashMap<>());

        final var resp = UserOperationHelper.processChangePermissions(changeReq);
        assertEquals(OperationStatus.OK, resp.getStatus());

        final var user = cache.getAdminUserEntry("permChangeUser");
        assertNotNull(user);
        assertTrue(user.isAdmin());
    }

    @Test
    public void test_change_permissions_user_not_found() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("nonexistent");
        final var resp = UserOperationHelper.processChangePermissions(req);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }
}
