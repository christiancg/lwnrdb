package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationLocks;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FencedCollectionReadTest {
    private static final long LOCK_TIMEOUT_MS = 200L;

    private final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final String collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private Thread holder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LOCK_TIMEOUT_MS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        release.countDown();
        if (holder != null) {
            holder.join(5_000L);
        }
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs",
                ConfigKey.TRANSACTION_LOCK_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheWriteLockFenced() throws InterruptedException {
        holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                locks.fence(List.of(collId));
                held.countDown();
                release.await();
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
    }

    private OperationResponse read() {
        return OperationLocks.withReadLocks(false, List.of(collId), OperationType.AGGREGATE,
                ErrorCode.ERROR_AGGREGATING,
                () -> new OperationResponse(OperationType.AGGREGATE, OperationStatus.OK, "read"));
    }

    private OperationResponse pointRead() {
        return OperationLocks.withPointReadLock(false, collId, OperationType.FIND_BY_ID, ErrorCode.ERROR_AGGREGATING,
                false, () -> new OperationResponse(OperationType.FIND_BY_ID, OperationStatus.OK, "read"));
    }

    @Test
    public void aReadOfAFencedCollectionAnswersInsteadOfWaiting() throws Exception {
        holdTheWriteLockFenced();

        final var started = System.nanoTime();
        final var answer = read();
        final var point = pointRead();

        assertEquals(ErrorCode.COLLECTION_FENCED.getCode(), answer.getErrorCode());
        assertEquals(ErrorCode.COLLECTION_FENCED.getCode(), point.getErrorCode());
        assertTrue((System.nanoTime() - started) / 1_000_000L < 10 * LOCK_TIMEOUT_MS);
    }

    @Test
    public void anAggregateOfAFencedCollectionAnswersInsteadOfWaiting() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        holdTheWriteLockFenced();
        final var count = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        count.setAggregationSteps(List.of(new CountAggregationStep()));
        final var scan = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        scan.setAggregationSteps(List.of(new LimitAggregationStep(5)));

        assertEquals(ErrorCode.COLLECTION_FENCED.getCode(),
                ReadPathHelper.processAggregateOperation(count, null).getErrorCode());
        assertEquals(ErrorCode.COLLECTION_FENCED.getCode(),
                ReadPathHelper.processAggregateOperation(scan, null).getErrorCode());
    }

    @Test
    public void aReadAlreadyWaitingWhenTheFenceIsSetAnswersInsteadOfHanging() throws Exception {
        final var fenceNow = new CountDownLatch(1);
        holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                held.countDown();
                fenceNow.await();
                locks.fence(List.of(collId));
                release.await();
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        final var answer = new OperationResponse[1];
        final var reader = Thread.ofPlatform().start(() -> answer[0] = read());
        Thread.sleep(LOCK_TIMEOUT_MS);

        fenceNow.countDown();
        reader.join(10 * LOCK_TIMEOUT_MS);

        assertFalse(reader.isAlive(), "a read waiting before the fence must not wait out the whole commit");
        assertEquals(ErrorCode.COLLECTION_FENCED.getCode(), answer[0].getErrorCode());
    }

    @Test
    public void releasingTheFenceLetsReadsWaitAgain() throws Exception {
        holdTheWriteLockFenced();
        release.countDown();
        holder.join(5_000L);

        assertFalse(locks.fences(List.of(collId)));
        assertEquals(OperationStatus.OK, read().getStatus());
    }

    @Test
    public void aFenceNeedsTheWriteLock() {
        locks.fence(List.of(collId));

        assertFalse(locks.fences(List.of(collId)));
    }
}
