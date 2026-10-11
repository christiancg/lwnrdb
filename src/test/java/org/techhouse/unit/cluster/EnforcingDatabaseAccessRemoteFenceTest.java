package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessRemoteFenceTest extends RemoteOwnerTxTestBase {
    private static final String ADMIN = "remote-fence-admin";

    @Override
    String collectionPrefix() {
        return "remote-fence-";
    }

    @Override
    void beforeClusterStart() {
        TestUtils.createAdmin(ADMIN);
    }

    private EnforcingDatabaseAccess scriptThatSavedRemotely(UUID scriptClientId, String id) throws Exception {
        final var database = new EnforcingDatabaseAccess(ADMIN, scriptClientId);
        database.beginTransaction();
        database.save(TestGlobals.DB, coll, document(id));
        makeTheOwnerTheOnlyNode();
        return database;
    }

    private static void commitOnAnEdgeSharingNoDiskWithTheOwner(EnforcingDatabaseAccess database) {
        try (var edgeMarkers = Mockito.mockStatic(org.techhouse.ops.tx.SliceStateMachine.class,
                Mockito.CALLS_REAL_METHODS)) {
            edgeMarkers.when(() -> org.techhouse.ops.tx.SliceStateMachine.isFenced(anyString())).thenReturn(false);
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
        makeTheOwnerTheOnlyNode();
        removeCollectionFolder();

        commitOnAnEdgeSharingNoDiskWithTheOwner(database);

        assertTrue(database.lastCommitWasFenced());
        assertTheSliceIsStillReachable(sessionClientId);
        clientTracker.removeById(sessionClientId);
    }
}
