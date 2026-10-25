package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TransactionRecordCalls;

public class TransactionOperationHelperReapTest {
    private static final String STRANDED = TestGlobals.DB + Globals.COLL_IDENTIFIER_SEPARATOR + "stranded";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        clientId = clientTracker.addClient(TestUtils.clientSocket());
    }

    @AfterEach
    public void tearDown() throws Exception {
        clientTracker.removeById(clientId);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void openTransactionWithOneWrite(String id) {
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        save.setObject(object);
        save.set_id(id);
        processor.processMessage(save, clientId);
    }

    private static boolean writableFromAnotherThread() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = new boolean[1];
        final var probe = Thread.ofPlatform().start(() -> {
            free[0] = locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL);
            if (free[0]) {
                locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        probe.join(5000);
        return free[0];
    }

    @Test
    public void test_reap_releases_locks_and_clears_the_registration() throws Exception {
        openTransactionWithOneWrite("reaped");
        final var opIds = List.copyOf(clientTracker.getActiveTransaction(clientId).getBufferedOpIds());
        assertFalse(writableFromAnotherThread());

        assertTrue(TransactionOperationHelper.reapIdleTransaction(clientId));

        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(writableFromAnotherThread());
        assertTrue(AdminOperationHelper.readTransactionOps(opIds).isEmpty());
    }

    @Test
    public void test_reap_of_a_fenced_transaction_changes_nothing() throws Exception {
        openTransactionWithOneWrite("fenced");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var txId = transaction.getTransactionId().toString();
        TransactionRecordCalls.persistBufferedOps(transaction);
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        try {
            assertFalse(TransactionOperationHelper.isReapable(clientId));

            assertFalse(TransactionOperationHelper.reapIdleTransaction(clientId));

            assertNotNull(clientTracker.getActiveTransaction(clientId));
            assertTrue(TxCommitLog.isLocallyCommitted(txId));
            assertFalse(writableFromAnotherThread());
            assertEquals(1, AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds()).size());
        } finally {
            TxCommitLog.clearLocalCommit(txId);
        }
    }

    @Test
    public void test_reap_that_strands_a_lock_keeps_the_registration() throws Exception {
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var owner = Thread.ofPlatform().start(() -> {
            try {
                locks.lockWrite(STRANDED);
                held.countDown();
                release.await();
                locks.releaseWrite(STRANDED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            transaction.getHeldLocks().add(STRANDED);

            assertFalse(TransactionOperationHelper.reapIdleTransaction(clientId));

            assertNotNull(clientTracker.getActiveTransaction(clientId));
            assertTrue(transaction.getHeldLocks().contains(STRANDED));
        } finally {
            release.countDown();
            owner.join(5000);
        }
    }

    @Test
    public void test_reap_without_a_transaction_returns_true() {
        assertTrue(TransactionOperationHelper.reapIdleTransaction(clientId));
        assertFalse(TransactionOperationHelper.isReapable(clientId));
    }

    @Test
    public void test_only_a_live_transaction_is_reapable() {
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);

        assertTrue(TransactionOperationHelper.isReapable(clientId));
        transaction.markAborted();
        assertFalse(TransactionOperationHelper.isReapable(clientId));
    }
}
