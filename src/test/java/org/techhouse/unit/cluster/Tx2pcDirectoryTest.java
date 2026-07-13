package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.Tx2pcDirectory;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.InDoubtTx;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class Tx2pcDirectoryTest {
    private static final String SELF_ADDRESS = "127.0.0.1:5000";
    private static final String OTHER_ADDRESS = "127.0.0.1:5001";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcDirectory directory = IocContainer.get(Tx2pcDirectory.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private boolean origEnabled;
    private PeerConnectionPool origPool;

    private static NodeInfo self() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    private static NodeInfo other() {
        return new NodeInfo("other", "127.0.0.1", 5001, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origPool = TestUtils.getPrivateField(directory, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", self())));
        TestUtils.setPrivateField(membershipService, "self", self());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(directory, "pool", origPool);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String seedPrepared(String coordinator, List<String> participants) throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        Tx2pcLog.recordParticipantPrepared(dtxId, coordinator, participants,
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
        return dtxId;
    }

    private void withOtherMember() throws Exception {
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", self(), "other", other())));
    }

    private void poolReturns(List<InDoubtTx> remoteInDoubt) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(_ -> {
            final var reply = new ClusterMessage();
            reply.setType(ClusterMessageType.LIST_TX_ACK);
            reply.setInDoubtTransactions(remoteInDoubt);
            return reply;
        });
        TestUtils.setPrivateField(directory, "pool", pool);
    }

    private void poolThrows() throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenThrow(new java.io.IOException("unreachable"));
        TestUtils.setPrivateField(directory, "pool", pool);
    }

    @Test
    public void test_local_in_doubt_maps_prepared_markers() throws Exception {
        final var dtxId = seedPrepared(SELF_ADDRESS, List.of(SELF_ADDRESS, OTHER_ADDRESS));
        final var local = directory.localInDoubt();
        assertEquals(1, local.size());
        final var tx = local.getFirst();
        assertEquals(dtxId, tx.getDtxId());
        assertEquals(SELF_ADDRESS, tx.getCoordinator());
        assertEquals("PREPARED", tx.getStatus());
        assertTrue(tx.getParticipants().contains(OTHER_ADDRESS));
        assertTrue(tx.getPreparedAt() > 0);
    }

    @Test
    public void test_local_in_doubt_empty_when_no_markers() throws Exception {
        assertTrue(directory.localInDoubt().isEmpty());
    }

    @Test
    public void test_cluster_wide_reachable_coordinator() throws Exception {
        final var dtxId = seedPrepared(SELF_ADDRESS, List.of(SELF_ADDRESS));
        final var rows = directory.listInDoubtClusterWide();
        assertEquals(1, rows.size());
        final var row = rows.getFirst();
        assertEquals(dtxId, row.get("dtxId").asJsonString().getValue());
        assertTrue(row.get("coordinatorReachable").asJsonBoolean().getValue());
        assertEquals("PREPARED", row.get("perNodeStatus").asJsonObject().get(SELF_ADDRESS).asJsonString().getValue());
    }

    @Test
    public void test_cluster_wide_unreachable_coordinator() throws Exception {
        seedPrepared("127.0.0.1:9999", List.of("127.0.0.1:9999", SELF_ADDRESS));
        final var rows = directory.listInDoubtClusterWide();
        assertEquals(1, rows.size());
        assertFalse(rows.getFirst().get("coordinatorReachable").asJsonBoolean().getValue());
    }

    @Test
    public void test_cluster_wide_aggregates_peer_status() throws Exception {
        final var dtxId = seedPrepared(SELF_ADDRESS, List.of(SELF_ADDRESS, OTHER_ADDRESS));
        withOtherMember();
        poolReturns(List.of(new InDoubtTx(dtxId, SELF_ADDRESS, List.of(SELF_ADDRESS, OTHER_ADDRESS),
                System.currentTimeMillis(), "PREPARED")));
        final var rows = directory.listInDoubtClusterWide();
        assertEquals(1, rows.size());
        final var perNodeStatus = rows.getFirst().get("perNodeStatus").asJsonObject();
        assertEquals("PREPARED", perNodeStatus.get(SELF_ADDRESS).asJsonString().getValue());
        assertEquals("PREPARED", perNodeStatus.get(OTHER_ADDRESS).asJsonString().getValue());
    }

    @Test
    public void test_cluster_wide_skips_unreachable_peer() throws Exception {
        final var dtxId = seedPrepared(SELF_ADDRESS, List.of(SELF_ADDRESS));
        withOtherMember();
        poolThrows();
        final var rows = directory.listInDoubtClusterWide();
        assertEquals(1, rows.size());
        assertEquals(dtxId, rows.getFirst().get("dtxId").asJsonString().getValue());
        assertEquals(1, rows.getFirst().get("perNodeStatus").asJsonObject().entrySet().size());
    }

    @Test
    public void test_cluster_wide_disabled_returns_local_only() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        withOtherMember();
        poolThrows();
        final var dtxId = seedPrepared(SELF_ADDRESS, List.of(SELF_ADDRESS));
        final var rows = directory.listInDoubtClusterWide();
        assertEquals(1, rows.size());
        assertEquals(dtxId, rows.getFirst().get("dtxId").asJsonString().getValue());
    }

    @Test
    public void test_cluster_wide_empty_when_no_in_doubt() throws Exception {
        assertTrue(directory.listInDoubtClusterWide().isEmpty());
    }
}
