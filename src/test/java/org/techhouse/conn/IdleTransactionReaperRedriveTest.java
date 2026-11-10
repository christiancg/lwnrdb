package org.techhouse.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.DeferredWrites;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IdleTransactionReaperRedriveTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private UUID clientId;
    private IdleTransactionReaper reaper;
    private BooleanSupplier stopWhen = () -> false;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        DeferredWrites.install();
        assertEquals(OperationStatus.OK, processor.processMessage(save("seed")).getStatus());
        clientId = clientTracker.registerForwardedClient("reaper-redriver");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        TransactionOperationHelper.bufferSave(save("fenced"), clientTracker.getActiveTransaction(clientId));
        reaper = new IdleTransactionReaper(clientId, () -> "test-client", this::shouldStop);
        writablePages(false);
        assertEquals("500-33", TransactionOperationHelper.commit(clientId).getErrorCode());
    }

    @AfterEach
    public void tearDown() throws Exception {
        writablePages(true);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        if (transaction != null) {
            TxCommitLog.clearLocalCommit(transaction.getTransactionId().toString());
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private boolean shouldStop() {
        return stopWhen.getAsBoolean();
    }

    private static SaveRequest save(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        return request;
    }

    private static void writablePages(boolean writable) {
        final var files = new File(TestGlobals.PATH + "/" + TestGlobals.DB + "/" + TestGlobals.COLL)
                .listFiles((_, name) -> name.endsWith(".dat"));
        for (final var page : files == null ? new File[0] : files) {
            assertTrue(page.setWritable(writable));
        }
    }

    @Test
    public void aFencedCommitReadsWithABackoffAndIsRedrivenOnTimeout() {
        final var first = reaper.nextReadBudget();
        assertTrue(first.redrive());
        assertEquals(Globals.FENCE_REDRIVE_INITIAL_MS, first.millis());

        assertFalse(reaper.redrive());
        assertEquals(Globals.FENCE_REDRIVE_INITIAL_MS * 2, reaper.nextReadBudget().millis());
        writablePages(true);

        assertTrue(reaper.redrive());
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertFalse(reaper.nextReadBudget().redrive());
    }

    @Test
    public void aDisconnectedFenceKeepsBeingRedrivenWhileTheDiskIsFull() {
        final var checks = new int[1];
        stopWhen = () -> ++checks[0] > 3;

        reaper.redriveUntilFinished();

        assertTrue(checks[0] > 3);
        assertNotNull(clientTracker.getActiveTransaction(clientId), "the disk is still full");
    }

    @Test
    public void aDisconnectedFenceStopsBeingRedrivenAtShutdown() {
        stopWhen = () -> true;

        reaper.redriveUntilFinished();

        assertNotNull(clientTracker.getActiveTransaction(clientId), "shutdown leaves it to startup recovery");
    }

    @Test
    public void aDisconnectedFenceFinishesOnceTheDiskFrees() throws Exception {
        final var freer = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writablePages(true);
        });

        reaper.redriveUntilFinished();
        freer.join();

        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void aRedriveAfterShutdownStartsLeavesTheFenceToStartupRecovery() {
        writablePages(true);
        stopWhen = () -> true;

        assertFalse(reaper.redrive());

        assertNotNull(clientTracker.getActiveTransaction(clientId), "a commit at shutdown would lose its index events");
    }

    @Test
    public void aRedriveCountsAsAnInFlightRequest() throws Exception {
        final var inFlight = Mockito.mock(InFlightRequests.class);
        TestUtils.setPrivateField(reaper, "inFlightRequests", inFlight);
        writablePages(true);

        assertTrue(reaper.redrive());

        final var order = Mockito.inOrder(inFlight);
        order.verify(inFlight).enter();
        order.verify(inFlight).exit();
    }
}
