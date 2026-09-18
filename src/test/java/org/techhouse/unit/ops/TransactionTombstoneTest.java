package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionTombstoneTest {
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", node())));
        TestUtils.setPrivateField(membershipService, "self", node());
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void saveGone() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("gone"));
        request.setObject(object);
        request.set_id("gone");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    @Test
    public void test_delete_tombstones_are_written_before_the_ops_apply() throws Exception {
        saveGone();
        final var clientId = clientTracker.registerForwardedClient("tx");
        TransactionOperationHelper.start(clientId);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("gone");
        TransactionOperationHelper.bufferDelete(delete, clientTracker.getActiveTransaction(clientId));

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).containsKey("gone"),
                "a committed transactional delete must leave a tombstone behind");
    }

    @Test
    public void test_tombstones_are_written_even_when_ownership_moved() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        transaction.recordDelete(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL), "moved");
        ownership.setSelfNodeId("someone-else");
        ownership.onMembershipChanged(
                new MembershipView(List.of(new NodeInfo("someone-else", "127.0.0.1", 5001, NodeState.ALIVE, 1L, 1L))));

        coordinator.reserveTransactionTombstones(transaction);

        assertTrue(fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).containsKey("moved"),
                "a tombstone is local durability, not replication: ownership must not gate it");
    }
}
