package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BoundedCollectionLockTest {

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private CountDownLatch release;
    private Thread holder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.join(5000);
            release = null;
            holder = null;
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheCollectionLockOnAnotherThread() throws InterruptedException {
        final var held = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "the holder thread must take the lock before the test proceeds");
    }

    private static OperationResponse attemptWith(boolean bounded, AtomicBoolean ran) {
        return OperationLocks.withCollectionLock(TestGlobals.DB, TestGlobals.COLL, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, bounded, () -> {
                    ran.set(true);
                    return OperationResponse.ok(OperationType.CREATE_INDEX, "done");
                });
    }

    @Test
    public void test_a_bounded_acquisition_gives_up_instead_of_parking_the_peer_thread() throws Exception {
        holdTheCollectionLockOnAnotherThread();
        final var ran = new AtomicBoolean(false);

        final var startedAt = System.currentTimeMillis();
        final var response = attemptWith(true, ran);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode(),
                "a replicated admin op must answer rather than park, or one idle transaction silences the"
                        + " whole inbound channel from that peer");
        assertFalse(ran.get(), "the body must not run without the lock");
        assertTrue(System.currentTimeMillis() - startedAt < 30000, "it must give up on its own budget");
    }

    @Test
    public void test_a_bounded_acquisition_runs_normally_when_the_lock_is_free() {
        final var ran = new AtomicBoolean(false);

        final var response = attemptWith(true, ran);

        assertEquals(OperationStatus.OK, response.getStatus(), "a free lock must be taken and the body run");
        assertTrue(ran.get(), "the body must run when the lock was available");
    }

    @Test
    public void test_an_unbounded_acquisition_still_runs_normally_when_the_lock_is_free() {
        final var ran = new AtomicBoolean(false);

        final var response = attemptWith(false, ran);

        assertEquals(OperationStatus.OK, response.getStatus(),
                "a client request keeps waiting for its lock, so the unbounded path must be unchanged");
        assertTrue(ran.get(), "the body must run when the lock was available");
    }

    @Test
    public void test_a_bounded_acquisition_releases_the_lock_it_took() {
        attemptWith(true, new AtomicBoolean(false));

        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                "the collection must be free again once the bounded attempt returns");
        locks.release(TestGlobals.DB, TestGlobals.COLL);
    }
}
