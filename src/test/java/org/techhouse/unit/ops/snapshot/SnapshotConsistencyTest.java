package org.techhouse.unit.ops.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.StartSnapshotResponse;
import org.techhouse.test.TestUtils;

public class SnapshotConsistencyTest {
    private static final long WAIT_MS = 10_000L;
    private final SnapshotTestSupport support = new SnapshotTestSupport();
    private final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ExecutorService transactionThread = Executors.newSingleThreadExecutor();

    @BeforeEach
    public void setUp() throws Exception {
        support.setUp();
    }

    @AfterEach
    public void tearDown() throws Exception {
        transactionThread.shutdownNow();
        assertTrue(transactionThread.awaitTermination(WAIT_MS, TimeUnit.MILLISECONDS));
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs",
                ConfigKey.TRANSACTION_LOCK_TIMEOUT_MS.defaultValue());
        support.tearDown();
    }

    private OperationResponse onTransactionThread(UUID clientId, String json) throws Exception {
        return transactionThread.submit(() -> support.send(clientId, json)).get(WAIT_MS, TimeUnit.MILLISECONDS);
    }

    private UUID openTransactionWritingBothCollections() throws Exception {
        final var writer = support.connect();
        assertEquals(OperationStatus.OK, onTransactionThread(writer, "{\"type\":\"START_TRANSACTION\"}").getStatus());
        for (final var coll : List.of(SnapshotTestSupport.COLL, SnapshotTestSupport.OTHER)) {
            final var saved = onTransactionThread(writer,
                    "{\"type\":\"SAVE\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\",\"collectionName\":\""
                            + coll + "\",\"object\":{\"_id\":\"tx\"," + "\"v\":\"committed\"}}");
            assertEquals(OperationStatus.OK, saved.getStatus(), saved.getMessage());
        }
        return writer;
    }

    private CompletableFuture<OperationResponse> startInBackground() {
        return CompletableFuture.supplyAsync(
                () -> support.send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}"),
                command -> Thread.ofVirtual().start(command));
    }

    @Test
    public void test_start_waits_for_a_transaction_spanning_two_collections_and_sees_all_of_it() throws Exception {
        final var writer = openTransactionWritingBothCollections();
        final var started = startInBackground();
        Thread.sleep(200);
        assertFalse(started.isDone(), "the snapshot waits for the transaction's write locks");

        assertEquals(OperationStatus.OK, onTransactionThread(writer, "{\"type\":\"COMMIT_TRANSACTION\"}").getStatus());
        final var snapshotId = ((StartSnapshotResponse) started.get(WAIT_MS, TimeUnit.MILLISECONDS)).getSnapshotId();
        support.save("tx", "after");
        support.delete(SnapshotTestSupport.OTHER, "tx");

        assertEquals(List.of("tx=\"committed\""),
                support.summaries(support.readAll(snapshotId, SnapshotTestSupport.COLL, 10)));
        assertEquals(List.of("tx=\"committed\""),
                support.summaries(support.readAll(snapshotId, SnapshotTestSupport.OTHER, 10)));
    }

    @Test
    public void test_a_snapshot_taken_before_a_transaction_sees_none_of_it() throws Exception {
        final var snapshotId = support.start();
        final var writer = openTransactionWritingBothCollections();

        assertEquals(OperationStatus.OK, onTransactionThread(writer, "{\"type\":\"COMMIT_TRANSACTION\"}").getStatus());

        assertTrue(support.readAll(snapshotId, SnapshotTestSupport.COLL, 10).isEmpty());
        assertTrue(support.readAll(snapshotId, SnapshotTestSupport.OTHER, 10).isEmpty());
    }

    @Test
    public void test_start_times_out_with_409_5_while_a_transaction_holds_a_collection() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 200L);
        final var writer = openTransactionWritingBothCollections();
        try {
            final var refused = support
                    .send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}");

            assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), refused.getErrorCode(), refused.getMessage());
            assertEquals(0, support.registry.openCount());
        } finally {
            onTransactionThread(writer, "{\"type\":\"ROLLBACK_TRANSACTION\"}");
        }
    }

    @Test
    public void test_a_fenced_collection_answers_503_18_without_waiting_out_the_lock_timeout() throws Exception {
        final var snapshotId = support.start();
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var collId = Cache.getCollectionIdentifier(SnapshotTestSupport.DB, SnapshotTestSupport.OTHER);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(SnapshotTestSupport.DB, SnapshotTestSupport.OTHER);
                locks.fence(List.of(collId));
                held.countDown();
                release.await();
                locks.release(SnapshotTestSupport.DB, SnapshotTestSupport.OTHER);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(held.await(WAIT_MS, TimeUnit.MILLISECONDS));
            final var started = System.nanoTime();

            final var refusedStart = support
                    .send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}");
            final var refusedRead = support.read(support.client, snapshotId, SnapshotTestSupport.OTHER, null, 5);

            final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(ErrorCode.COLLECTION_FENCED.getCode(), refusedStart.getErrorCode(), refusedStart.getMessage());
            assertEquals(ErrorCode.COLLECTION_FENCED.getCode(), refusedRead.getErrorCode(), refusedRead.getMessage());
            assertTrue(elapsedMs < configuration.getTransactionLockTimeoutMs(), elapsedMs + " ms");
            assertEquals(OperationStatus.OK,
                    support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5).getStatus());
        } finally {
            release.countDown();
            holder.join(WAIT_MS);
        }
    }
}
