package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class DropDatabaseBarrierTest {
    private static final String LATE_COLL = "lateColl";
    private static final long BUDGET_MS = 1000L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private long originalTimeout;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTimeout = Configuration.getInstance().getTransactionLockTimeoutMs();
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs", BUDGET_MS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ReentrantReadWriteLock barrier() throws Exception {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(locks, "locks", type).get(TestGlobals.DB);
    }

    private CompletableFuture<OperationResponse> dropAsync() {
        return CompletableFuture.supplyAsync(() -> processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)));
    }

    private Thread holder(CountDownLatch held, CountDownLatch release, Runnable acquire, Runnable relinquish) {
        final var thread = new Thread(() -> {
            acquire.run();
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                relinquish.run();
            }
        });
        thread.start();
        return thread;
    }

    private void holdBarrierExclusive() throws InterruptedException {
        assertTrue(locks.tryLockDatabaseExclusive(TestGlobals.DB, BUDGET_MS));
    }

    @Test
    public void test_create_collection_times_out_while_a_drop_holds_the_database() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var thread = holder(held, release, () -> {
            try {
                holdBarrierExclusive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> locks.releaseDatabaseExclusive(TestGlobals.DB));
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, LATE_COLL));

            assertEquals("409-5", response.getErrorCode());
            assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, LATE_COLL));
        } finally {
            release.countDown();
            thread.join(5000);
        }
    }

    @Test
    public void test_drop_gives_up_on_a_held_collection_lock_and_releases_everything() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var thread = holder(held, release, () -> locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                () -> locks.release(TestGlobals.DB, TestGlobals.COLL));
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            final var response = dropAsync().get(10, TimeUnit.SECONDS);

            assertEquals("409-5", response.getErrorCode());
            assertNotNull(cache.getAdminDbEntry(TestGlobals.DB), "a drop that gave up must leave the database");
            assertTrue(locks.tryLockDatabaseExclusive(TestGlobals.DB, 0), "the drop must have released the barrier");
            locks.releaseDatabaseExclusive(TestGlobals.DB);
        } finally {
            release.countDown();
            thread.join(5000);
        }
    }

    @Test
    public void test_drop_locks_a_collection_registered_while_it_waited_for_the_database() throws Exception {
        final var registered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var inFlightCreate = new Thread(() -> {
            try {
                assertTrue(locks.tryLockDatabaseShared(TestGlobals.DB, BUDGET_MS));
                awaitQueuedDrop();
                AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, LATE_COLL));
                IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, LATE_COLL);
                assertTrue(locks.tryLockWrite(TestGlobals.DB, LATE_COLL, BUDGET_MS));
                locks.releaseDatabaseShared(TestGlobals.DB);
                registered.countDown();
                release.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                locks.release(TestGlobals.DB, LATE_COLL);
            }
        });
        inFlightCreate.start();
        waitUntilBarrierIsShared();
        final var drop = dropAsync();
        assertTrue(registered.await(10, TimeUnit.SECONDS));
        try {
            final var response = drop.get(10, TimeUnit.SECONDS);

            assertEquals("409-5", response.getErrorCode(),
                    "the drop must wait on the late collection's lock instead of deleting it unlocked");
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, LATE_COLL));
        } finally {
            release.countDown();
            inFlightCreate.join(5000);
        }
        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());
    }

    private void waitUntilBarrierIsShared() throws Exception {
        final var deadline = System.currentTimeMillis() + 5000;
        while (barrier() == null || barrier().getReadLockCount() == 0) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.onSpinWait();
        }
    }

    private void awaitQueuedDrop() throws Exception {
        final var deadline = System.currentTimeMillis() + 5000;
        while (!barrier().hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.onSpinWait();
        }
    }
}
