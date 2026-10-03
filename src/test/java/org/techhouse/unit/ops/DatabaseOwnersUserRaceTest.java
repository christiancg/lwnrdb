package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DatabaseOwnersUserRaceTest {
    private static final long BUDGET_MS = 10_000L;
    private static final String NEW_DB = "ownerracedb";
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean usersLockHeld = new AtomicBoolean();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        createUser("gamma");
        createUser("delta");
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

    private static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(username + "-password");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        assertEquals(OperationStatus.OK, UserOperationHelper.processCreateUser(request).getStatus());
    }

    private static SetDatabaseOwnersRequest ownersRequest(List<String> owners, boolean replicated) {
        final var request = new SetDatabaseOwnersRequest(TestGlobals.DB);
        request.setOwners(owners);
        request.setReplicated(replicated);
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

    private void deleteUserOnHolder(String username) throws Exception {
        onHolder(() -> {
            AdminOperationHelper.deleteUserEntry(username);
            return null;
        });
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

    @Test
    public void test_set_owners_refuses_a_user_deleted_while_it_waited() throws Exception {
        holdUsersLock();
        final var result = new AtomicReference<OperationResponse>();
        final var setOwners = startParked(
                () -> DatabaseOperationHelper.processSetDatabaseOwners(ownersRequest(List.of("gamma"), false)), result);

        deleteUserOnHolder("gamma");
        releaseUsersLock();
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
        releaseUsersLock();
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
    public void test_replicated_set_owners_is_not_filtered() {
        final var response = DatabaseOperationHelper
                .processSetDatabaseOwners(ownersRequest(List.of("not_on_this_node"), true));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(List.of("not_on_this_node"), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }

    @Test
    public void test_set_owners_of_existing_users_is_written() {
        final var response = DatabaseOperationHelper
                .processSetDatabaseOwners(ownersRequest(List.of("gamma", "delta"), false));

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
