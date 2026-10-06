package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.techhouse.test.ClusterTestHarness.node;

import java.io.File;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
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
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessRemoteFenceTest {
    private static final String ADMIN = "remote-fence-admin";
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
        createAdmin();
        cluster.start(false, 10_000L);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        coll = collectionOwnedByOther();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
        clientId = newClient();
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

    private static void createAdmin() {
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var candidate = "remote-fence-" + i;
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

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    private EnforcingDatabaseAccess scriptThatSavedRemotely(UUID scriptClientId, String id) throws Exception {
        final var database = new EnforcingDatabaseAccess(ADMIN, scriptClientId);
        database.beginTransaction();
        database.save(TestGlobals.DB, coll, document(id));
        cluster.configureMembership(1, node("other", cluster.serverPort()));
        return database;
    }

    private void removeCollectionFolder() {
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + coll);
        final var files = folder.listFiles();
        if (files != null) {
            for (final var file : files) {
                assertTrue(file.delete(), "could not delete " + file);
            }
        }
        assertTrue(folder.delete(), "could not delete " + folder);
    }

    private OperationStatus findStatus(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id(id);
        return processor.processMessage(find).getStatus();
    }

    private static void commitOnAnEdgeSharingNoDiskWithTheOwner(EnforcingDatabaseAccess database) {
        try (var edgeMarkers = Mockito.mockStatic(TransactionOperationHelper.class, Mockito.CALLS_REAL_METHODS)) {
            edgeMarkers.when(() -> TransactionOperationHelper.isFenced(anyString())).thenReturn(false);
            assertThrows(JsThrowException.class, database::commitTransaction);
        }
    }

    private void assertTheSliceIsStillReachable(UUID edgeClientId) {
        assertNotNull(clientTracker.getActiveTransaction(edgeClientId),
                "the owner still holds the slice, so the edge must keep the transaction");
        assertFalse(clientTracker.transactionParticipants(edgeClientId).isEmpty(),
                "a re-sent COMMIT can only reach the owner through the recorded participant");
    }

    @Test
    public void a_script_commit_the_owner_fenced_keeps_the_edge_transaction_and_its_participant() throws Exception {
        final var database = scriptThatSavedRemotely(clientId, "fenced");
        removeCollectionFolder();

        commitOnAnEdgeSharingNoDiskWithTheOwner(database);

        assertTrue(database.lastCommitWasFenced());
        assertTheSliceIsStillReachable(clientId);
    }

    @Test
    public void a_resent_wire_commit_then_finishes_the_slice() throws Exception {
        final var database = scriptThatSavedRemotely(clientId, "finished");
        removeCollectionFolder();
        commitOnAnEdgeSharingNoDiskWithTheOwner(database);

        fs.createCollectionFile(TestGlobals.DB, coll);
        final var commit = new CommitTransactionRequest();
        final var resent = router.forward(commit, eJson.toJson(commit), true, ADMIN, clientId);

        assertNull(eJson.fromJson(resent, OperationResponse.class).getErrorCode(),
                "the re-sent COMMIT must finish the slice, got: " + resent);
        assertEquals(OperationStatus.OK, findStatus("finished"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void an_ordinary_script_commit_to_a_sole_owner_still_clears_the_edge() throws Exception {
        final var database = scriptThatSavedRemotely(clientId, "ordinary");

        assertDoesNotThrow(database::commitTransaction);

        assertFalse(database.lastCommitWasFenced());
        assertEquals(OperationStatus.OK, findStatus("ordinary"));
        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
    }

    @Test
    public void a_script_on_a_transient_client_keeps_the_fenced_session() throws Exception {
        final var database = new EnforcingDatabaseAccess(ADMIN, null);
        database.beginTransaction();
        final var sessionClientId = TestUtils.getPrivateField(database, "sessionClientId", UUID.class);
        database.save(TestGlobals.DB, coll, document("transient"));
        cluster.configureMembership(1, node("other", cluster.serverPort()));
        removeCollectionFolder();

        commitOnAnEdgeSharingNoDiskWithTheOwner(database);

        assertTrue(database.lastCommitWasFenced());
        assertTheSliceIsStillReachable(sessionClientId);
        clientTracker.removeById(sessionClientId);
    }
}
