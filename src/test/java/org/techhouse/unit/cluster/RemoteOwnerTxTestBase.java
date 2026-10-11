package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.node;

import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

abstract class RemoteOwnerTxTestBase {
    final ClusterTestHarness cluster = new ClusterTestHarness();
    final Configuration config = Configuration.getInstance();
    final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    final FileSystem fs = IocContainer.get(FileSystem.class);
    final EJson eJson = IocContainer.get(EJson.class);
    String coll;
    UUID clientId;

    abstract String collectionPrefix();

    void beforeClusterStart() {
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        beforeClusterStart();
        cluster.start(false, 10_000L);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        coll = collectionOwnedByOther();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
        clientId = clientTracker.addClient(TestUtils.clientSocket());
    }

    @AfterEach
    public void tearDown() throws Exception {
        releaseSessions();
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    void releaseSessions() throws Exception {
        for (final var session : clientTracker.txSessionsSnapshot().values()) {
            final var transaction = clientTracker.getActiveTransaction(session.clientId());
            if (transaction != null) {
                TxCommitLog.clearLocalCommit(transaction.getTransactionId().toString());
            }
        }
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var candidate = collectionPrefix() + i;
            if (!ownership.isOwner(TestGlobals.DB, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    void makeTheOwnerTheOnlyNode() throws Exception {
        cluster.configureMembership(1, node("other", cluster.serverPort()));
    }

    void removeCollectionFolder() {
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

    OperationStatus findStatus(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id(id);
        return processor.processMessage(find).getStatus();
    }
}
