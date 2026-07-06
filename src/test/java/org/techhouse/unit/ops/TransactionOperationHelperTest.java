package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionOperationHelperTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private SaveRequest saveRequest(String id) {
        final var req = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        req.setObject(obj);
        req.set_id(id);
        return req;
    }

    @Test
    public void test_isAllowedDuringTransaction_whitelist() {
        for (final var allowed : new OperationType[]{OperationType.START_TRANSACTION, OperationType.COMMIT_TRANSACTION,
                OperationType.ROLLBACK_TRANSACTION, OperationType.SAVE, OperationType.BULK_SAVE, OperationType.DELETE,
                OperationType.FIND_BY_ID, OperationType.AGGREGATE, OperationType.CLOSE_CONNECTION}) {
            assertTrue(TransactionOperationHelper.isAllowedDuringTransaction(allowed), allowed + " should be allowed");
        }
        for (final var blocked : new OperationType[]{OperationType.CREATE_COLLECTION, OperationType.DROP_COLLECTION,
                OperationType.CREATE_INDEX, OperationType.LISTEN, OperationType.CREATE_DATABASE}) {
            assertFalse(TransactionOperationHelper.isAllowedDuringTransaction(blocked), blocked + " should be blocked");
        }
    }

    @Test
    public void test_lock_timeout_auto_rolls_back_and_returns_409_5() throws Exception {
        final var config = Configuration.getInstance();
        final var originalTimeout = config.getTransactionLockTimeoutMs();
        // Shrink the lock-acquisition timeout so the buffered write aborts quickly instead of waiting
        // the multi-second production default.
        TestUtils.setPrivateField(config, "transactionLockTimeoutMs", 200L);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);

        // Hold the collection write lock from another thread so the transaction's first buffered write
        // cannot acquire it within the timeout.
        final var acquired = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            if (locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL)) {
                acquired.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lock holder timed out waiting for the release signal");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
                }
            }
        });
        holder.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        try {
            final var response = processor.processMessage(saveRequest("txn-timeout-1"), clientId);
            assertEquals("409-5", response.getErrorCode());
            // The transaction was auto-rolled-back, so it is no longer active.
            assertNull(clientTracker.getActiveTransaction(clientId));
        } finally {
            release.countDown();
            holder.join(5000);
            TestUtils.setPrivateField(config, "transactionLockTimeoutMs", originalTimeout);
        }
    }

    @Test
    public void test_cleanup_on_disconnect_clears_transaction_and_releases_locks() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-disc-1"), clientId);

        TransactionOperationHelper.cleanupOnDisconnect(clientId);

        assertNull(clientTracker.getActiveTransaction(clientId));
        // Lock released — reacquire and release directly.
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_cleanup_on_disconnect_no_transaction_is_noop() {
        final var clientId = newClient();
        TransactionOperationHelper.cleanupOnDisconnect(clientId);
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_cleanup_orphans_at_startup_removes_buffered_ops() throws Exception {
        final var cache = IocContainer.get(org.techhouse.cache.Cache.class);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("txn-orphan-1"), clientId);
        // Simulate a crash: the buffered op record is on disk/in the index, but we drop the in-memory
        // transaction without committing or rolling back.
        final var transaction = clientTracker.getActiveTransaction(clientId);
        for (final var collId : transaction.getHeldLocks()) {
            locks.releaseWrite(collId);
        }
        clientTracker.clearActiveTransaction(clientId);
        assertFalse(cache.getTransactionPkIndexes().isEmpty());

        TransactionOperationHelper.cleanupOrphansAtStartup();

        assertTrue(cache.getTransactionPkIndexes().isEmpty());
        // A second run with nothing buffered exercises the empty early-return path.
        TransactionOperationHelper.cleanupOrphansAtStartup();
        assertTrue(cache.getTransactionPkIndexes().isEmpty());
    }

    @Test
    public void test_apply_overlay_to_stream_without_overlay_returns_committed() {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        final var doc = new JsonObject();
        doc.add("_id", new JsonString("x"));
        final var result = TransactionOperationHelper
                .applyOverlayToStream(transaction, "myDb|myCollection", Stream.of(doc)).toList();
        assertEquals(1, result.size());
    }
}
