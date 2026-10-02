package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestUtils;

public class UserOperationHelperConcurrencyTest {
    private static final long BUDGET_MS = 10_000L;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean usersLockHeld = new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        createUser("alpha", true);
        createUser("beta", true);
        createUser("gamma", false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (usersLockHeld.get()) {
            releaseUsersLock();
        }
        holder.shutdown();
        assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS));
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

        releaseUsersLock();
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

        releaseUsersLock();
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
        final var createOne = startParked(() -> UserOperationHelper.processCreateUser(createRequest("delta", "one")),
                first);
        final var createTwo = startParked(() -> UserOperationHelper.processCreateUser(createRequest("delta", "two")),
                second);

        releaseUsersLock();
        createOne.join(BUDGET_MS);
        createTwo.join(BUDGET_MS);

        final var statuses = List.of(first.get().getStatus(), second.get().getStatus());
        assertTrue(statuses.contains(OperationStatus.OK));
        final var refused = first.get().getStatus() == OperationStatus.OK ? second.get() : first.get();
        assertEquals(ErrorCode.USER_ALREADY_EXISTS.getCode(), refused.getErrorCode());
        final var winner = first.get().getStatus() == OperationStatus.OK ? "one" : "two";
        assertTrue(PasswordHasher.verify(winner + "-password", cache.getAdminUserEntry("delta").getPasswordHash()));
    }

    @Test
    public void test_a_permission_change_waiting_on_a_delete_does_not_resurrect_the_user() throws Exception {
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var change = startParked(() -> UserOperationHelper.processChangePermissions(permissionsRequest("gamma")),
                result);

        onHolder(() -> {
            AdminOperationHelper.deleteUserEntry("gamma");
            return null;
        });
        releaseUsersLock();
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

        onHolder(() -> {
            final var current = cache.getAdminUserEntry("gamma");
            AdminOperationHelper.saveUserEntry(new AdminUserEntry("gamma", current.getPasswordHash(), false,
                    Set.of(GlobalPermissionType.CREATE_DATABASE), new HashMap<>(), new HashMap<>()));
            return null;
        });
        releaseUsersLock();
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

        onHolder(() -> {
            final var current = cache.getAdminUserEntry("gamma");
            AdminOperationHelper.saveUserEntry(new AdminUserEntry("gamma", PasswordHasher.hash("reset-by-admin"), false,
                    current.getGlobalPermissions(), current.getDatabasePermissions(),
                    current.getCollectionPermissions()));
            return null;
        });
        releaseUsersLock();
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

        releaseUsersLock();
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
        onHolder(() -> {
            locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
            return null;
        });
        usersLockHeld.set(true);
    }

    private void releaseUsersLock() throws Exception {
        onHolder(() -> {
            locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
            return null;
        });
        usersLockHeld.set(false);
    }

    private void onHolder(Callable<Void> action) throws Exception {
        holder.submit(action).get(BUDGET_MS, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("BusyWait")
    private static Thread startParked(Callable<OperationResponse> action, AtomicReference<OperationResponse> result)
            throws InterruptedException {
        final var thread = new Thread(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        final var deadline = System.currentTimeMillis() + BUDGET_MS;
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the request never parked on the users lock");
            Thread.sleep(5);
        }
        return thread;
    }
}
