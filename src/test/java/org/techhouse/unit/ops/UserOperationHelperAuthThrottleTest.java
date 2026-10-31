package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.techhouse.test.LockHolder.startParked;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.auth.AuthFailureThrottle;
import org.techhouse.ops.auth.PasswordCheckGate;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.Await;
import org.techhouse.test.LockHolder;
import org.techhouse.test.TestUtils;

public class UserOperationHelperAuthThrottleTest {
    private static final String USER = "throttled_user";
    private static final String PASSWORD = "password123";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final AuthFailureThrottle throttle = IocContainer.get(AuthFailureThrottle.class);
    private final PasswordCheckGate gate = IocContainer.get(PasswordCheckGate.class);
    private final Configuration config = Configuration.getInstance();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<UUID> clients = new ArrayList<>();
    private LongSupplier realClock;
    private Semaphore realPermits;
    private Semaphore permits;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createAdmin(USER);
        TestUtils.setPrivateField(config, "maxConnections", 0);
        realClock = TestUtils.getPrivateField(throttle, "clock", LongSupplier.class);
        TestUtils.setPrivateField(throttle, "clock", (LongSupplier) now::get);
        realPermits = TestUtils.getPrivateField(gate, "permits", Semaphore.class);
        permits = spy(new Semaphore(gate.size(), true));
        TestUtils.setPrivateField(gate, "permits", permits);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clients.forEach(clientTracker::removeById);
        TestUtils.setPrivateField(throttle, "clock", realClock);
        TestUtils.setPrivateField(gate, "permits", realPermits);
        TestUtils.setPrivateField(config, "maxConnections", "100");
        TestUtils.setPrivateField(config, "maxFailedAuthAttempts", "5");
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID connect() {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        clients.add(clientId);
        return clientId;
    }

    private static AuthenticateRequest request(String username, String password) {
        final var request = new AuthenticateRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private OperationResponse attempt(UUID clientId, String username, String password) {
        now.addAndGet(AuthFailureThrottle.IDLE_EXPIRY_MILLIS / 2);
        return UserOperationHelper.processAuthenticate(request(username, password), clientId);
    }

    private static String userKey() {
        return AuthFailureThrottle.keyOf("127.0.0.1", USER);
    }

    @Test
    public void unknown_user_answers_401_3_and_runs_a_dummy_verification() throws Exception {
        final var response = attempt(connect(), "nobody_at_all", PASSWORD);

        assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), response.getErrorCode());
        verify(permits, times(1)).acquire();
    }

    @Test
    public void nth_failure_answers_401_4() {
        final var clientId = connect();
        final var codes = new ArrayList<String>();
        for (var i = 0; i < 5; i++) {
            codes.add(attempt(clientId, USER, "wrong").getErrorCode());
        }

        final var wrong = ErrorCode.WRONG_CREDENTIALS.getCode();
        assertEquals(List.of(wrong, wrong, wrong, wrong, ErrorCode.TOO_MANY_FAILED_ATTEMPTS.getCode()), codes);
        assertEquals(OperationStatus.UNAUTHENTICATED, ErrorCode.TOO_MANY_FAILED_ATTEMPTS.getStatus());
    }

    @Test
    public void unknown_user_counts_toward_the_cap_like_a_known_one() {
        final var clientId = connect();
        for (var i = 0; i < 4; i++) {
            attempt(clientId, "ghost_" + i, "wrong");
        }

        assertEquals(ErrorCode.TOO_MANY_FAILED_ATTEMPTS.getCode(),
                attempt(clientId, "ghost_last", "wrong").getErrorCode());
    }

    @Test
    public void cap_zero_never_answers_401_4() throws Exception {
        TestUtils.setPrivateField(config, "maxFailedAuthAttempts", "0");
        final var clientId = connect();
        for (var i = 0; i < 8; i++) {
            assertEquals(ErrorCode.WRONG_CREDENTIALS.getCode(), attempt(clientId, USER, "wrong").getErrorCode());
        }
    }

    @Test
    public void failure_count_survives_a_success_on_the_same_connection() {
        final var clientId = connect();
        for (var i = 0; i < 4; i++) {
            attempt(clientId, USER, "wrong");
        }
        assertEquals(OperationStatus.OK, attempt(clientId, USER, PASSWORD).getStatus());

        assertEquals(ErrorCode.TOO_MANY_FAILED_ATTEMPTS.getCode(), attempt(clientId, USER, "wrong").getErrorCode());
    }

    @Test
    public void failed_attempt_records_throttle_failure_and_success_clears_it() {
        final var clientId = connect();
        attempt(clientId, USER, "wrong");
        assertTrue(throttle.remainingDelayMillis(userKey()) > 0);
        attempt(clientId, USER, "wrong");
        assertTrue(throttle.remainingDelayMillis(userKey()) > 0);

        assertEquals(OperationStatus.OK, attempt(clientId, USER, PASSWORD).getStatus());

        assertEquals(0, throttle.remainingDelayMillis(userKey()));
        assertEquals(USER, clientTracker.getAuthenticatedUsername(clientId));
    }

    @Test
    public void delay_is_requested_before_verification_for_a_key_with_prior_failures() throws Exception {
        final var clientId = connect();
        throttle.recordFailure(userKey());
        final var response = new AtomicReference<OperationResponse>();
        final var interruptKept = new AtomicBoolean();
        final var thread = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            response.set(UserOperationHelper.processAuthenticate(request(USER, PASSWORD), clientId));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        thread.join(10_000);

        assertEquals(ErrorCode.AUTHENTICATION_ERROR.getCode(), response.get().getErrorCode());
        assertTrue(interruptKept.get(), "the interrupt flag must be restored");
        verify(permits, never()).acquire();
        assertEquals(gate.size(), permits.availablePermits());
        assertNull(clientTracker.getAuthenticatedUsername(clientId));
    }

    @Test
    public void bind_still_happens_inside_the_users_lock() throws Exception {
        final var clientId = connect();
        final var locks = IocContainer.get(ResourceLocking.class);
        try (var holder = new LockHolder()) {
            holder.hold(() -> {
                locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
                return true;
            }, () -> locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME));
            final var result = new AtomicReference<OperationResponse>();
            final var authenticate = startParked(
                    () -> UserOperationHelper.processAuthenticate(request(USER, PASSWORD), clientId), result);
            verify(permits, timeout(10_000)).release();
            assertTrue(Await.until(() -> permits.availablePermits() == gate.size(), 10_000),
                    "no permit is held across the users lock");

            assertTrue(authenticate.isAlive());
            assertNull(clientTracker.getAuthenticatedUsername(clientId));
            holder.release();
            authenticate.join(10_000);

            assertEquals(OperationStatus.OK, result.get().getStatus());
            assertEquals(USER, clientTracker.getAuthenticatedUsername(clientId));
        }
    }
}
