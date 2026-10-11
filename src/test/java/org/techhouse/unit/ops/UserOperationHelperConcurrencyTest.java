package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.test.LockHolder.startParked;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.LockHolder;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UserOperationHelperConcurrencyTest {
    private static final long BUDGET_MS = 10_000L;
    private static final String NEW_DB = "ownerracedb";
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final LockHolder holder = new LockHolder();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        createUser("alpha", true);
        createUser("beta", true);
        createUser("gamma", false);
        createUser("delta", false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_two_concurrent_deletes_of_the_last_two_admins_leave_one_admin() throws Exception {
        holdUsersLock();
        final var first = new AtomicReference<OperationResponse>();
        final var second = new AtomicReference<OperationResponse>();
        final var deleteAlpha = startParked(() -> UserOperationHelper.processDeleteUser(deleteRequest("alpha")), first);
        final var deleteBeta = startParked(() -> UserOperationHelper.processDeleteUser(deleteRequest("beta")), second);

        holder.release();
        deleteAlpha.join(BUDGET_MS);
        deleteBeta.join(BUDGET_MS);

        final var codes = List.of(String.valueOf(first.get().getErrorCode()),
                String.valueOf(second.get().getErrorCode()));
        assertTrue(codes.contains(ErrorCode.CANNOT_DELETE_LAST_ADMIN.getCode()), codes.toString());
        assertEquals(1, cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count());
    }

    @Test
    public void test_two_concurrent_demotions_of_the_last_two_admins_leave_one_admin() throws Exception {
        holdUsersLock();
        final var first = new AtomicReference<OperationResponse>();
        final var second = new AtomicReference<OperationResponse>();
        final var demoteAlpha = startParked(
                () -> UserOperationHelper.processChangePermissions(permissionsRequest("alpha")), first);
        final var demoteBeta = startParked(
                () -> UserOperationHelper.processChangePermissions(permissionsRequest("beta")), second);

        holder.release();
        demoteAlpha.join(BUDGET_MS);
        demoteBeta.join(BUDGET_MS);

        final var codes = List.of(String.valueOf(first.get().getErrorCode()),
                String.valueOf(second.get().getErrorCode()));
        assertTrue(codes.contains(ErrorCode.CANNOT_DEMOTE_LAST_ADMIN.getCode()), codes.toString());
        assertEquals(1, cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count());
    }

    @Test
    public void test_two_concurrent_creates_of_one_name_answer_already_exists_once() throws Exception {
        holdUsersLock();
        final var first = new AtomicReference<OperationResponse>();
        final var second = new AtomicReference<OperationResponse>();
        final var createOne = startParked(() -> UserOperationHelper.processCreateUser(createRequest("epsilon", "one")),
                first);
        final var createTwo = startParked(() -> UserOperationHelper.processCreateUser(createRequest("epsilon", "two")),
                second);

        holder.release();
        createOne.join(BUDGET_MS);
        createTwo.join(BUDGET_MS);

        final var statuses = List.of(first.get().getStatus(), second.get().getStatus());
        assertTrue(statuses.contains(OperationStatus.OK));
        final var refused = first.get().getStatus() == OperationStatus.OK ? second.get() : first.get();
        assertEquals(ErrorCode.USER_ALREADY_EXISTS.getCode(), refused.getErrorCode());
        final var winner = first.get().getStatus() == OperationStatus.OK ? "one" : "two";
        assertTrue(PasswordHasher.verify(winner + "-password", cache.getAdminUserEntry("epsilon").getPasswordHash()));
    }

    @Test
    public void test_a_permission_change_waiting_on_a_delete_does_not_resurrect_the_user() throws Exception {
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var change = startParked(() -> UserOperationHelper.processChangePermissions(permissionsRequest("gamma")),
                result);

        deleteUserOnHolder("gamma");
        holder.release();
        change.join(BUDGET_MS);

        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertNull(cache.getAdminUserEntry("gamma"));
    }

    @Test
    public void test_a_password_change_keeps_a_permission_change_that_landed_while_it_waited() throws Exception {
        final var callerId = IocContainer.get(ClientTracker.class).registerForwardedClient("alpha");
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var setPassword = startParked(
                () -> UserOperationHelper.processSetPassword(renewGammaPassword(), callerId), result);

        holder.run(() -> {
            final var current = cache.getAdminUserEntry("gamma");
            AdminOperationHelper.saveUserEntry(new AdminUserEntry("gamma", current.getPasswordHash(), false,
                    Set.of(GlobalPermissionType.CREATE_DATABASE), new HashMap<>(), new HashMap<>()));
            return null;
        });
        holder.release();
        setPassword.join(BUDGET_MS);

        assertEquals(OperationStatus.OK, result.get().getStatus());
        final var stored = cache.getAdminUserEntry("gamma");
        assertTrue(PasswordHasher.verify("renewed-password", stored.getPasswordHash()));
        assertEquals(Set.of(GlobalPermissionType.CREATE_DATABASE), stored.getGlobalPermissions());
    }

    @Test
    public void test_an_own_password_change_is_refused_when_the_password_changed_while_it_waited() throws Exception {
        final var callerId = IocContainer.get(ClientTracker.class).registerForwardedClient("gamma");
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var request = renewGammaPassword();
        request.setCurrentPassword("gamma-password");
        final var setPassword = startParked(() -> UserOperationHelper.processSetPassword(request, callerId), result);

        holder.run(() -> {
            final var current = cache.getAdminUserEntry("gamma");
            AdminOperationHelper.saveUserEntry(new AdminUserEntry("gamma", PasswordHasher.hash("reset-by-admin"), false,
                    current.getGlobalPermissions(), current.getDatabasePermissions(),
                    current.getCollectionPermissions()));
            return null;
        });
        holder.release();
        setPassword.join(BUDGET_MS);

        assertEquals(ErrorCode.CURRENT_PASSWORD_INCORRECT.getCode(), result.get().getErrorCode());
        assertTrue(PasswordHasher.verify("reset-by-admin", cache.getAdminUserEntry("gamma").getPasswordHash()));
    }

    @Test
    public void test_two_concurrent_deletes_of_one_user_answer_not_found_once() throws Exception {
        holdUsersLock();
        final var first = new AtomicReference<OperationResponse>();
        final var second = new AtomicReference<OperationResponse>();
        final var deleteOne = startParked(() -> UserOperationHelper.processDeleteUser(deleteRequest("gamma")), first);
        final var deleteTwo = startParked(() -> UserOperationHelper.processDeleteUser(deleteRequest("gamma")), second);

        holder.release();
        deleteOne.join(BUDGET_MS);
        deleteTwo.join(BUDGET_MS);

        final var codes = List.of(String.valueOf(first.get().getErrorCode()),
                String.valueOf(second.get().getErrorCode()));
        assertTrue(codes.contains(ErrorCode.USER_NOT_FOUND.getCode()), codes.toString());
        assertNull(cache.getAdminUserEntry("gamma"));
    }

    @Test
    public void test_deleting_an_absent_user_entry_is_a_no_op() throws Exception {
        AdminOperationHelper.deleteUserEntry("nobody");

        assertNotNull(cache.getAdminUserEntry("alpha"));
    }

    @Test
    public void test_authenticate_refuses_a_user_deleted_during_the_hash() throws Exception {
        final var clientId = clientTracker.registerForwardedClient(null);
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var authenticate = startParked(
                () -> UserOperationHelper.processAuthenticate(authenticateGamma(), clientId), result);

        deleteUserOnHolder("gamma");
        holder.release();
        authenticate.join(BUDGET_MS);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), result.get().getErrorCode());
        assertNull(clientTracker.getAuthenticatedUsername(clientId));
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_authenticate_refuses_a_password_changed_during_the_hash() throws Exception {
        final var clientId = clientTracker.registerForwardedClient(null);
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var authenticate = startParked(
                () -> UserOperationHelper.processAuthenticate(authenticateGamma(), clientId), result);

        holder.run(() -> {
            final var gamma = cache.getAdminUserEntry("gamma");
            AdminOperationHelper.saveUserEntry(new AdminUserEntry("gamma", PasswordHasher.hash("renewed-password"),
                    gamma.isAdmin(), gamma.getGlobalPermissions(), gamma.getDatabasePermissions(),
                    gamma.getCollectionPermissions(), gamma.getScriptPermissions()));
            return null;
        });
        holder.release();
        authenticate.join(BUDGET_MS);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), result.get().getErrorCode());
        assertNull(clientTracker.getAuthenticatedUsername(clientId));
        clientTracker.removeById(clientId);
    }

    @Test
    public void test_authenticate_binds_when_the_entry_is_unchanged() {
        final var clientId = clientTracker.registerForwardedClient(null);

        final var response = UserOperationHelper.processAuthenticate(authenticateGamma(), clientId);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("gamma", clientTracker.getAuthenticatedUsername(clientId));
        clientTracker.removeById(clientId);
    }

    private static AuthenticateRequest authenticateGamma() {
        final var request = new AuthenticateRequest();
        request.setUsername("gamma");
        request.setPassword("gamma-password");
        return request;
    }

    private void createUser(String username, boolean admin) {
        final var request = createRequest(username, username);
        request.setAdmin(admin);
        assertEquals(OperationStatus.OK, UserOperationHelper.processCreateUser(request).getStatus());
    }

    private static CreateUserRequest createRequest(String username, String passwordPrefix) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(passwordPrefix + "-password");
        request.setAdmin(false);
        request.setGlobalPermissions(new java.util.HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        return request;
    }

    private static DeleteUserRequest deleteRequest(String username) {
        final var request = new DeleteUserRequest();
        request.setUsername(username);
        return request;
    }

    private static ChangePermissionsRequest permissionsRequest(String username) {
        final var request = new ChangePermissionsRequest();
        request.setUsername(username);
        request.setAdmin(false);
        request.setGlobalPermissions(new java.util.HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        return request;
    }

    private static SetPasswordRequest renewGammaPassword() {
        final var request = new SetPasswordRequest();
        request.setUsername("gamma");
        request.setNewPassword("renewed-password");
        return request;
    }

    private void holdUsersLock() throws Exception {
        holder.hold(() -> {
            locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
            return true;
        }, () -> locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME));
    }

    private void deleteUserOnHolder(String username) throws Exception {
        holder.run(() -> {
            AdminOperationHelper.deleteUserEntry(username);
            return null;
        });
    }

    private static SetDatabaseOwnersRequest ownersRequest(List<String> owners) {
        final var request = new SetDatabaseOwnersRequest(TestGlobals.DB);
        request.setOwners(owners);
        return request;
    }

    @Test
    public void test_set_owners_refuses_a_user_deleted_while_it_waited() throws Exception {
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var setOwners = startParked(
                () -> DatabaseOperationHelper.processSetDatabaseOwners(ownersRequest(List.of("gamma"))), result);

        deleteUserOnHolder("gamma");
        holder.release();
        setOwners.join(BUDGET_MS);

        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertFalse(cache.getAdminDbEntry(TestGlobals.DB).getOwners().contains("gamma"),
                "a deleted name left as owner hands the database to the next user created under it");
    }

    @Test
    public void test_create_database_is_ownerless_when_its_caller_was_deleted_meanwhile() throws Exception {
        final var callerId = clientTracker.registerForwardedClient("delta");
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var create = startParked(() -> DatabaseOperationHelper
                .processCreateDatabaseOperation(new CreateDatabaseRequest(NEW_DB), callerId), result);

        deleteUserOnHolder("delta");
        holder.release();
        create.join(BUDGET_MS);

        assertEquals(OperationStatus.OK, result.get().getStatus());
        assertTrue(cache.getAdminDbEntry(NEW_DB).getOwners().isEmpty());
    }

    @Test
    public void test_create_database_is_owned_by_its_existing_caller() {
        final var callerId = clientTracker.registerForwardedClient("delta");

        DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(NEW_DB), callerId);

        assertEquals(List.of("delta"), cache.getAdminDbEntry(NEW_DB).getOwners());
    }

    @Test
    public void test_set_owners_of_existing_users_is_written() {
        final var response = DatabaseOperationHelper.processSetDatabaseOwners(ownersRequest(List.of("gamma", "delta")));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(List.of("gamma", "delta"), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }

    @Test
    public void test_set_owners_of_a_missing_database_answers_not_found() {
        final var request = new SetDatabaseOwnersRequest("no_such_db");
        request.setOwners(List.of("gamma"));

        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(),
                DatabaseOperationHelper.processSetDatabaseOwners(request).getErrorCode());
    }
}
