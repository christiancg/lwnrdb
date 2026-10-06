package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.node;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionFencedResendTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Configuration config = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        cluster.start(false, 1000L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private UUID transactionWithOneSave(String coll, String id) {
        final var clientId = clientTracker.registerForwardedClient("resender");
        TransactionOperationHelper.start(clientId);
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(document(id));
        request.set_id(id);
        TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
        return clientId;
    }

    private UUID fencedTransaction(String coll, String id) throws Exception {
        final var clientId = transactionWithOneSave(coll, id);
        final var transaction = clientTracker.getActiveTransaction(clientId);
        TxCommitLog.recordLocalCommit(transaction.getTransactionId().toString(), transaction.getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, coll)));
        return clientId;
    }

    private void loseQuorum() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(3, node("self", cluster.serverPort()));
    }

    private void regainQuorum() throws Exception {
        cluster.configureMembership(1, node("self", cluster.serverPort()));
    }

    private String collectionOwnedByOther() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(2, node("self", cluster.serverPort()), node("other", 19991));
        for (var i = 0; i < 500; i++) {
            final var coll = "resend-moved-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
    }

    private void assertSliceRetained(UUID clientId, String txId, List<String> opIds, String coll) {
        assertTrue(TxCommitLog.isLocallyCommitted(txId), "the commit marker must survive the refused re-send");
        assertTrue(opIds.stream().allMatch(id -> cache.getPkIndexTransaction(id) != null),
                "every op the marker names must survive the refused re-send");
        assertNotNull(clientTracker.getActiveTransaction(clientId),
                "the transaction stays registered so its locks keep an owner");
        assertTrue(locks.isWriteLockedByCurrentThread(Cache.getCollectionIdentifier(TestGlobals.DB, coll)),
                "the half-applied collection must stay locked");
    }

    @Test
    public void test_resent_commit_without_quorum_keeps_the_fenced_slice() throws Exception {
        final var clientId = fencedTransaction(TestGlobals.COLL, "no-quorum");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        loseQuorum();

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("500-33", response.getErrorCode(),
                "a decided commit that cannot finish yet is still half applied, not refused");
        assertSliceRetained(clientId, txId, opIds, TestGlobals.COLL);
    }

    @Test
    public void test_resent_commit_after_ownership_moved_keeps_the_fenced_slice() throws Exception {
        final var coll = collectionOwnedByOther();
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        createCollection(coll);
        final var clientId = fencedTransaction(coll, "moved");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        final var txId = transaction.getTransactionId().toString();
        final var opIds = List.copyOf(transaction.getBufferedOpIds());
        TestUtils.setPrivateField(config, "clusterEnabled", true);

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("500-33", response.getErrorCode(),
                "a decided commit on a collection this node no longer owns must stay half applied");
        assertSliceRetained(clientId, txId, opIds, coll);
    }

    @Test
    public void test_resent_commit_finishes_once_quorum_returns() throws Exception {
        final var clientId = fencedTransaction(TestGlobals.COLL, "healed");
        final var txId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        loseQuorum();
        TransactionOperationHelper.commit(clientId);
        regainQuorum();

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals(OperationStatus.OK, response.getStatus(), "the re-send must finish the decided commit");
        assertFalse(TxCommitLog.isLocallyCommitted(txId), "a finished commit clears its marker");
        assertNull(clientTracker.getActiveTransaction(clientId), "a finished commit deregisters the transaction");
        assertFalse(locks.isWriteLockedByCurrentThread(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)),
                "a finished commit releases its locks");
        assertNotNull(
                cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                        .filter(entry -> entry.getValue().equals("healed")).findFirst().orElse(null),
                "the committed document must be present");
    }

    @Test
    public void test_unfenced_commit_without_quorum_still_discards_its_ops() throws Exception {
        final var clientId = transactionWithOneSave(TestGlobals.COLL, "undecided");
        final var opIds = List.copyOf(clientTracker.getActiveTransaction(clientId).getBufferedOpIds());
        loseQuorum();

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals("503-2", response.getErrorCode(), "an undecided commit without quorum is refused as before");
        assertFalse(opIds.stream().anyMatch(id -> cache.getPkIndexTransaction(id) != null),
                "the refused commit discards its buffered ops");
        assertNull(clientTracker.getActiveTransaction(clientId), "the refused commit deregisters the transaction");
    }
}
