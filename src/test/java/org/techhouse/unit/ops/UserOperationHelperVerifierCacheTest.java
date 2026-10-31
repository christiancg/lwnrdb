package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.admin.RestampedRecords;
import org.techhouse.ops.auth.AuthFailureThrottle;
import org.techhouse.ops.auth.PasswordCheckGate;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.Await;
import org.techhouse.test.TestUtils;

public class UserOperationHelperVerifierCacheTest {
    private static final String USER = "cached_user";
    private static final String PASSWORD = "password123";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final AuthFailureThrottle throttle = IocContainer.get(AuthFailureThrottle.class);
    private final PasswordCheckGate gate = IocContainer.get(PasswordCheckGate.class);
    private final Configuration config = Configuration.getInstance();
    private final List<UUID> clients = new ArrayList<>();
    private Semaphore realPermits;
    private Semaphore permits;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createAdmin("verifier_admin");
        TestUtils.createAdmin(USER);
        TestUtils.setPrivateField(config, "maxConnections", 0);
        TestUtils.setPrivateField(config, "maxFailedAuthAttempts", "0");
        realPermits = TestUtils.getPrivateField(gate, "permits", Semaphore.class);
        permits = spy(new Semaphore(gate.size(), true));
        TestUtils.setPrivateField(gate, "permits", permits);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clients.forEach(clientTracker::removeById);
        TestUtils.setPrivateField(gate, "permits", realPermits);
        TestUtils.setPrivateField(config, "maxConnections", "100");
        TestUtils.setPrivateField(config, "maxFailedAuthAttempts", "5");
        TestUtils.setPrivateField(config, "authVerifierCacheEnabled", "true");
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID connect() {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        clients.add(clientId);
        return clientId;
    }

    private OperationResponse authenticate(String password) {
        final var request = new AuthenticateRequest();
        request.setUsername(USER);
        request.setPassword(password);
        return UserOperationHelper.processAuthenticate(request, connect());
    }

    private static String userKey() {
        return AuthFailureThrottle.keyOf("127.0.0.1", USER);
    }

    private void hashesRun(int expected) throws InterruptedException {
        verify(permits, times(expected)).acquire();
        clearInvocations(permits);
    }

    @Test
    public void a_repeated_success_skips_the_hash() throws Exception {
        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());
        hashesRun(1);

        final var clientId = connect();
        final var request = new AuthenticateRequest();
        request.setUsername(USER);
        request.setPassword(PASSWORD);
        assertEquals(OperationStatus.OK, UserOperationHelper.processAuthenticate(request, clientId).getStatus());

        hashesRun(0);
        assertEquals(USER, clientTracker.getAuthenticatedUsername(clientId));
    }

    @Test
    public void a_wrong_password_after_a_cached_success_pays_the_hash_and_is_throttled() throws Exception {
        authenticate(PASSWORD);
        authenticate(PASSWORD);
        hashesRun(1);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), authenticate("wrong-password").getErrorCode());

        hashesRun(1);
        assertTrue(throttle.remainingDelayMillis(userKey()) > 0);
    }

    @Test
    public void a_cached_success_clears_the_throttle_like_a_hashed_one() throws Exception {
        authenticate(PASSWORD);
        throttle.recordFailure(userKey());
        clearInvocations(permits);

        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());

        hashesRun(0);
        assertEquals(0, throttle.remainingDelayMillis(userKey()));
    }

    @Test
    public void an_unknown_user_pays_the_dummy_hash_every_time() throws Exception {
        final var request = new AuthenticateRequest();
        request.setUsername("nobody_known");
        request.setPassword(PASSWORD);
        UserOperationHelper.processAuthenticate(request, connect());
        UserOperationHelper.processAuthenticate(request, connect());

        hashesRun(2);
    }

    @Test
    public void set_password_makes_the_old_password_fail() throws Exception {
        authenticate(PASSWORD);
        final var admin = connect();
        clientTracker.setAuthenticatedUser(admin, "verifier_admin");
        final var change = new SetPasswordRequest();
        change.setUsername(USER);
        change.setNewPassword("renewed-password");
        assertEquals(OperationStatus.OK, UserOperationHelper.processSetPassword(change, admin).getStatus());
        clearInvocations(permits);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), authenticate(PASSWORD).getErrorCode());
        assertEquals(OperationStatus.OK, authenticate("renewed-password").getStatus());
        assertEquals(OperationStatus.OK, authenticate("renewed-password").getStatus());
        hashesRun(2);
    }

    @Test
    public void a_user_deleted_and_recreated_with_the_same_password_misses_once() throws Exception {
        authenticate(PASSWORD);
        final var delete = new DeleteUserRequest();
        delete.setUsername(USER);
        assertEquals(OperationStatus.OK,
                UserOperationHelper.processDeleteUser(delete, new RestampedRecords()).getStatus());
        clearInvocations(permits);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), authenticate(PASSWORD).getErrorCode());
        TestUtils.createAdmin(USER);
        clearInvocations(permits);
        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());
        hashesRun(1);
        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());
        hashesRun(0);
    }

    @Test
    public void disabled_cache_hashes_every_attempt() throws Exception {
        TestUtils.setPrivateField(config, "authVerifierCacheEnabled", "false");

        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());
        assertEquals(OperationStatus.OK, authenticate(PASSWORD).getStatus());

        hashesRun(2);
    }

    @Test
    public void interrupted_while_waiting_for_the_hash_answers_an_error_and_keeps_the_flag() throws Exception {
        final var clientId = connect();
        permits.drainPermits();
        clearInvocations(permits);
        final var response = new AtomicReference<OperationResponse>();
        final var interruptKept = new AtomicBoolean();
        final var thread = Thread.ofVirtual().start(() -> {
            final var request = new AuthenticateRequest();
            request.setUsername(USER);
            request.setPassword(PASSWORD);
            response.set(UserOperationHelper.processAuthenticate(request, clientId));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(Await.parked(thread, Thread.State.WAITING, 10_000), "the attempt never waited for the hash");
        thread.interrupt();
        thread.join(10_000);

        assertEquals(ErrorCode.AUTHENTICATION_ERROR.getCode(), response.get().getErrorCode());
        assertTrue(interruptKept.get(), "the interrupt flag must be restored");
        assertNull(clientTracker.getAuthenticatedUsername(clientId));
        verify(permits, never()).release();
        permits.release(gate.size());
    }
}
