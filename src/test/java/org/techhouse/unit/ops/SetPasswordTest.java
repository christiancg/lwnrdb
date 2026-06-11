package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestUtils;

import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SetPasswordTest {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        createUser("alice", false);
        createUser("bob", false);
        createUser("adminuser", true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, boolean admin) {
        final var req = new CreateUserRequest();
        req.setUsername(username);
        req.setPassword("password123");
        req.setAdmin(admin);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);
    }

    private UUID authenticateAs(String username) {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        try {
            TestUtils.setPrivateField(
                    org.techhouse.config.Configuration.getInstance(), "maxConnections", 100);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final var clientId = clientTracker.addClient(socket);
        clientTracker.setAuthenticatedUser(clientId, username);
        return clientId;
    }

    @Test
    public void test_user_can_change_own_password() {
        final var clientId = authenticateAs("alice");
        final var req = new SetPasswordRequest();
        req.setUsername("alice");
        req.setCurrentPassword("password123");
        req.setNewPassword("newpassword456");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());

        // Restore original password for other tests
        final var restore = new SetPasswordRequest();
        restore.setUsername("alice");
        restore.setCurrentPassword("newpassword456");
        restore.setNewPassword("password123");
        UserOperationHelper.processSetPassword(restore, clientId);
    }

    @Test
    public void test_user_cannot_change_own_password_with_wrong_current() {
        final var clientId = authenticateAs("alice");
        final var req = new SetPasswordRequest();
        req.setUsername("alice");
        req.setCurrentPassword("wrongpassword");
        req.setNewPassword("newpassword456");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("Current password is incorrect"));
    }

    @Test
    public void test_user_cannot_change_another_users_password() {
        final var clientId = authenticateAs("alice");
        final var req = new SetPasswordRequest();
        req.setUsername("bob");
        req.setCurrentPassword("password123");
        req.setNewPassword("newpassword456");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.FORBIDDEN, resp.getStatus());
    }

    @Test
    public void test_admin_can_change_another_users_password_without_current() {
        final var clientId = authenticateAs("adminuser");
        final var req = new SetPasswordRequest();
        req.setUsername("bob");
        req.setNewPassword("adminchanged1");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());

        // Verify new password works
        assertTrue(PasswordHasher.verify("adminchanged1",
                cache.getAdminUserEntry("bob").getPasswordHash()));

        // Restore
        final var restore = new SetPasswordRequest();
        restore.setUsername("bob");
        restore.setNewPassword("password123");
        UserOperationHelper.processSetPassword(restore, clientId);
    }

    @Test
    public void test_admin_can_change_own_password() {
        final var clientId = authenticateAs("adminuser");
        final var req = new SetPasswordRequest();
        req.setUsername("adminuser");
        req.setNewPassword("adminupdated1");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.OK, resp.getStatus());

        // Restore
        final var restore = new SetPasswordRequest();
        restore.setUsername("adminuser");
        restore.setNewPassword("password123");
        UserOperationHelper.processSetPassword(restore, clientId);
    }

    @Test
    public void test_set_password_user_not_found() {
        final var clientId = authenticateAs("adminuser");
        final var req = new SetPasswordRequest();
        req.setUsername("nonexistent");
        req.setNewPassword("newpassword1");
        final var resp = UserOperationHelper.processSetPassword(req, clientId);
        assertEquals(OperationStatus.NOT_FOUND, resp.getStatus());
    }

    @Test
    public void test_old_password_no_longer_works_after_change() {
        final var clientId = authenticateAs("adminuser");
        final var req = new SetPasswordRequest();
        req.setUsername("bob");
        req.setNewPassword("changedpassword1");
        UserOperationHelper.processSetPassword(req, clientId);

        final var authReq = new AuthenticateRequest();
        authReq.setUsername("bob");
        authReq.setPassword("password123");
        final var authResp = UserOperationHelper.processAuthenticate(authReq, UUID.randomUUID());
        assertEquals(OperationStatus.ERROR, authResp.getStatus());

        // Restore
        final var restore = new SetPasswordRequest();
        restore.setUsername("bob");
        restore.setNewPassword("password123");
        UserOperationHelper.processSetPassword(restore, clientId);
    }

    @Test
    public void test_new_password_works_after_change() {
        final var clientId = authenticateAs("adminuser");
        final var req = new SetPasswordRequest();
        req.setUsername("bob");
        req.setNewPassword("freshpassword1");
        UserOperationHelper.processSetPassword(req, clientId);

        final var authReq = new AuthenticateRequest();
        authReq.setUsername("bob");
        authReq.setPassword("freshpassword1");
        final var authResp = UserOperationHelper.processAuthenticate(authReq, UUID.randomUUID());
        assertEquals(OperationStatus.OK, authResp.getStatus());

        // Restore
        final var restore = new SetPasswordRequest();
        restore.setUsername("bob");
        restore.setNewPassword("password123");
        UserOperationHelper.processSetPassword(restore, clientId);
    }

    @Test
    public void test_validator_requires_username() {
        final var req = new SetPasswordRequest();
        req.setNewPassword("newpassword1");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_validator_requires_new_password_min_length() {
        final var req = new SetPasswordRequest();
        req.setUsername("alice");
        req.setNewPassword("short");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_validator_valid_request() {
        final var req = new SetPasswordRequest();
        req.setUsername("alice");
        req.setNewPassword("newpassword1");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_parser_parses_set_password() {
        final var msg = "{\"type\":\"SET_PASSWORD\",\"username\":\"alice\"," +
                "\"currentPassword\":\"oldpass\",\"newPassword\":\"newpass123\"}";
        final var req = (SetPasswordRequest) RequestParser.parseRequest(msg);
        assertEquals("alice", req.getUsername());
        assertEquals("oldpass", req.getCurrentPassword());
        assertEquals("newpass123", req.getNewPassword());
    }
}
