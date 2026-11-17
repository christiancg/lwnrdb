package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.TxSession;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.DurableReplayIncompleteException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.DeferredWrites;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FencedSessionRedriveTest {
    private static final String SESSION = "fenced-session";
    private static final long WAIT_MS = 10_000L;
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private TxSession session;
    private String txId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        DeferredWrites.install();
        assertEquals(OperationStatus.OK, processor.processMessage(save("seed")).getStatus());
        session = clientTracker.registerTxSession(SESSION, "redriver", null);
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(session.clientId()));
        txId = session.submit(() -> {
            TransactionOperationHelper.start(session.clientId());
            TransactionOperationHelper.bufferSave(save("fenced-doc"),
                    clientTracker.getActiveTransaction(session.clientId()));
            return clientTracker.getActiveTransaction(session.clientId()).getTransactionId().toString();
        }).get();
        writablePages(false);
        assertEquals("500-33",
                session.submit(() -> TransactionOperationHelper.commit(session.clientId())).get().getErrorCode());
    }

    @AfterEach
    public void tearDown() throws Exception {
        writablePages(true);
        if (clientTracker.txSession(SESSION) != null) {
            session.submit(() -> TwoPhaseParticipant.redriveOnLiveSession(txId, WAIT_MS));
            TxCommitLog.clearLocalCommit(txId);
            clientTracker.removeTxSession(SESSION);
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static SaveRequest save(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
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
    public void aFencedForwardedSliceIsRedrivenOnItsSessionThread() throws Exception {
        writablePages(true);

        assertTrue(TwoPhaseParticipant.redriveOnLiveSession(txId, WAIT_MS));

        assertFalse(cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL, Set.of("fenced-doc")).isEmpty());
        assertNull(clientTracker.txSession(SESSION), "a finished session is removed");
        assertEquals(OperationStatus.OK, processor.processMessage(save("after")).getStatus(),
                "the session thread released the collection lock");
    }

    @Test
    public void aStillFailingRedriveLeavesTheSliceFenced() {
        assertThrows(DurableReplayIncompleteException.class,
                () -> TwoPhaseParticipant.redriveOnLiveSession(txId, WAIT_MS));

        assertEquals(session, clientTracker.txSession(SESSION));
    }

    @Test
    public void aTransactionWithNoLiveSessionIsLeftToItsOwnConnection() throws Exception {
        assertFalse(TwoPhaseParticipant.redriveOnLiveSession(UUID.randomUUID().toString(), WAIT_MS));
        assertEquals(session, clientTracker.txSession(SESSION));
    }
}
