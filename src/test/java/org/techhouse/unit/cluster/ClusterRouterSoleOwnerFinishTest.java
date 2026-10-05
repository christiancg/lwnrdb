package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.node;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterSoleOwnerFinishTest {
    private static final String UNREACHABLE_OWNER = "127.0.0.1:1";
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Configuration config = Configuration.getInstance();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private String coll;
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        cluster.start(false, 10_000L);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        coll = collectionOwnedByOther();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
        clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var session : clientTracker.txSessionsSnapshot().values()) {
            final var transaction = clientTracker.getActiveTransaction(session.clientId());
            if (transaction != null) {
                TxCommitLog.clearLocalCommit(transaction.getTransactionId().toString());
            }
        }
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var candidate = "sole-owner-" + i;
            if (!ownership.isOwner(TestGlobals.DB, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private UUID newClient() {
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private String route(OperationRequest request) {
        return router.forward(request, eJson.toJson(request), true, "admin", clientId);
    }

    private void bufferRemoteSave(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var save = new SaveRequest(TestGlobals.DB, coll);
        save.setObject(object);
        save.set_id(id);
        route(save);
    }

    private void makeTheOwnerTheOnlyNode() throws Exception {
        cluster.configureMembership(1, node("other", cluster.serverPort()));
    }

    private File collectionFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + coll);
    }

    private void removeCollectionFolder() {
        final var folder = collectionFolder();
        final var files = folder.listFiles();
        if (files != null) {
            for (final var file : files) {
                assertTrue(file.delete(), "could not delete " + file);
            }
        }
        assertTrue(folder.delete(), "could not delete " + folder);
    }

    private String errorCodeOf(String relayed) {
        return eJson.fromJson(relayed, OperationResponse.class).getErrorCode();
    }

    private String fenceTheRemoteCommit(String id) throws Exception {
        bufferRemoteSave(id);
        makeTheOwnerTheOnlyNode();
        removeCollectionFolder();
        return route(new CommitTransactionRequest());
    }

    private OperationStatus findStatus(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id(id);
        return processor.processMessage(find).getStatus();
    }

    private boolean edgeStillHoldsTheTransaction() {
        return clientTracker.getActiveTransaction(clientId) != null
                && !clientTracker.transactionParticipants(clientId).isEmpty();
    }

    @Test
    public void a_fenced_forwarded_commit_keeps_the_edge_transaction_and_a_resent_commit_finishes_it()
            throws Exception {
        final var relayed = fenceTheRemoteCommit("fenced");

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), errorCodeOf(relayed));
        assertTrue(edgeStillHoldsTheTransaction(),
                "the edge must keep the transaction so a re-sent COMMIT reaches the owner holding the slice");
        assertFalse(clientTracker.txSessionsSnapshot().isEmpty(), "the owner keeps the fenced session");

        fs.createCollectionFile(TestGlobals.DB, coll);
        final var resent = route(new CommitTransactionRequest());

        assertNull(errorCodeOf(resent), "the re-sent COMMIT must finish the slice, got: " + resent);
        assertEquals(OperationStatus.OK, findStatus("fenced"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty(), "finishing the slice releases the owner's session");
    }

    @Test
    public void a_rollback_after_a_fenced_forwarded_commit_reports_half_applied() throws Exception {
        fenceTheRemoteCommit("rolled");

        final var relayed = route(new RollbackTransactionRequest());

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), errorCodeOf(relayed),
                "a rollback must not report success for a slice recovery will still commit");
        assertTrue(edgeStillHoldsTheTransaction());
        final var session = clientTracker.txSessionsSnapshot().values().iterator().next();
        final var ownerTransaction = clientTracker.getActiveTransaction(session.clientId());
        assertNotNull(ownerTransaction, "the owner keeps the fenced slice");
        assertTrue(TxCommitLog.isLocallyCommitted(ownerTransaction.getTransactionId().toString()));
    }

    @Test
    public void an_ordinary_forwarded_commit_still_clears_the_edge() throws Exception {
        bufferRemoteSave("ordinary");
        makeTheOwnerTheOnlyNode();

        final var relayed = route(new CommitTransactionRequest());

        assertNull(errorCodeOf(relayed), "expected a clean commit, got: " + relayed);
        assertEquals(OperationStatus.OK, findStatus("ordinary"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void an_ordinary_forwarded_rollback_still_clears_the_edge() {
        bufferRemoteSave("discarded");

        final var relayed = route(new RollbackTransactionRequest());

        assertNull(errorCodeOf(relayed), "expected a clean rollback, got: " + relayed);
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("discarded"));
    }

    @Test
    public void an_unreachable_owner_still_clears_the_edge_on_commit() {
        clientTracker.addTransactionParticipant(clientId, UNREACHABLE_OWNER);

        final var relayed = route(new CommitTransactionRequest());

        assertEquals(ErrorCode.OWNER_UNREACHABLE.getCode(), errorCodeOf(relayed));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
    }

    @Test
    public void the_owner_holds_the_slice_only_on_a_fenced_or_indeterminate_answer() throws Exception {
        final Method decides = ClusterRouter.class.getDeclaredMethod("ownerStillHoldsTheSlice", String.class);
        decides.setAccessible(true);
        final var commit = OperationType.COMMIT_TRANSACTION;

        assertTrue((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.TRANSACTION_HALF_APPLIED))));
        assertTrue((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.TRANSACTION_INDETERMINATE))));
        assertTrue((boolean) decides.invoke(router, "not json"),
                "an unreadable answer keeps the transaction, since keeping it only lets the client retry");
        assertFalse((boolean) decides.invoke(router, eJson.toJson(OperationResponse.ok(commit, "committed"))));
        assertFalse((boolean) decides.invoke(router,
                eJson.toJson(new OperationResponse(commit, ErrorCode.NO_ACTIVE_TRANSACTION))));
    }
}
