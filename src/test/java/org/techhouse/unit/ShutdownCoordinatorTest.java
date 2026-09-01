package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ShutdownCoordinator;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.SocketServer;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ShutdownCoordinatorTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.resetClients();
        TestUtils.releaseAllLocks();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    // A fresh coordinator per test: shutdown() is deliberately one-shot.
    private static ShutdownCoordinator coordinator() {
        return new ShutdownCoordinator();
    }

    // stopAccepting on a server that never began serving must be a no-op rather than a failure, so the hook
    // still runs when startup did not get as far as listening.
    @Test
    public void test_shutdown_tolerates_a_server_that_never_served() {
        final var server = new SocketServer(0);

        assertDoesNotThrow(() -> coordinator().shutdown(server, null));
    }

    @Test
    public void test_shutdown_is_one_shot() {
        final var coordinator = coordinator();

        assertDoesNotThrow(() -> coordinator.shutdown(null, null));
        assertDoesNotThrow(() -> coordinator.shutdown(null, null));
    }

    // An open transaction holds its collection write lock. Leaving it held would strand the lock for the
    // startup sweep to find, so shutdown rolls it back.
    @Test
    public void test_shutdown_rolls_back_an_open_transaction_and_releases_its_lock() {
        final var clientId = clientTracker.registerForwardedClient("alice");
        TransactionOperationHelper.start(clientId);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("held"));
        request.set_id("held");
        TransactionOperationHelper.bufferSave(request, transaction);
        assertFalse(transaction.getHeldLocks().isEmpty(), "the buffered write should hold a lock");

        TransactionOperationHelper.rollbackOpenTransactionsAtShutdown();

        assertNull(clientTracker.getActiveTransaction(clientId));
        // Proven by a second transaction taking the same lock: a stranded lock would block here.
        final var nextClient = clientTracker.registerForwardedClient("bob");
        TransactionOperationHelper.start(nextClient);
        final var next = clientTracker.getActiveTransaction(nextClient);
        final var secondRequest = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        secondRequest.setObject(document("after"));
        secondRequest.set_id("after");
        final var response = TransactionOperationHelper.bufferSave(secondRequest, next);
        assertEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus());
        TransactionOperationHelper.rollback(nextClient);
    }

    @Test
    public void test_shutdown_leaves_no_active_transaction_behind() {
        final var clientId = clientTracker.registerForwardedClient("carol");
        TransactionOperationHelper.start(clientId, UUID.randomUUID(), 0);

        coordinator().shutdown(null, null);

        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_shutdown_drains_the_trigger_queue() {
        final var ran = new java.util.concurrent.atomic.AtomicInteger();
        triggerExecutor.start(_ -> ran.incrementAndGet());
        for (var i = 0; i < 10; i++) {
            triggerExecutor.submit(
                    new org.techhouse.bckg_ops.events.TriggerEvent(org.techhouse.bckg_ops.events.EventType.CREATED,
                            TestGlobals.DB, TestGlobals.COLL, "t", "p", false, List.of(), "alice", 0, null));
        }

        coordinator().shutdown(null, null);

        assertEquals(10, ran.get());
        assertEquals(0, triggerExecutor.pending());
    }

    // Shutdown stops workers; it must not tear down state the JVM may still touch on its way out.
    @Test
    public void test_cache_still_answers_after_shutdown() {
        coordinator().shutdown(null, null);

        assertNotNull(IocContainer.get(Cache.class).getUserDatabaseNames());
    }
}
