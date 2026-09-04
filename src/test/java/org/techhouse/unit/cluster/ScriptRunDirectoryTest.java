package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ScriptRunDirectory;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.RunningScript;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.test.TestUtils;

public class ScriptRunDirectoryTest {
    private static final String SELF_ADDRESS = "127.0.0.1:5000";
    private static final String OTHER_ADDRESS = "127.0.0.1:5001";
    private final Configuration config = Configuration.getInstance();
    private final ScriptRunDirectory directory = IocContainer.get(ScriptRunDirectory.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
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
        origEnabled = config.isClusterEnabled();
        origPool = TestUtils.getPrivateField(directory, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>(Map.of("self", self())));
        TestUtils.setPrivateField(membershipService, "self", self());
    }

    @AfterEach
    public void tearDown() throws Exception {
        registry.list().forEach(run -> registry.unregister(run.runId()));
        TestUtils.setPrivateField(directory, "pool", origPool);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    private void withOtherMember() throws Exception {
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(Map.of("self", self(), "other", other())));
    }

    private PeerConnectionPool poolReturning(ClusterMessage reply) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenReturn(reply);
        TestUtils.setPrivateField(directory, "pool", pool);
        return pool;
    }

    private PeerConnectionPool poolThrows() throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenThrow(new java.io.IOException("unreachable"));
        TestUtils.setPrivateField(directory, "pool", pool);
        return pool;
    }

    private static ClusterMessage listAck(RunningScript... runs) {
        final var reply = new ClusterMessage();
        reply.setType(ClusterMessageType.LIST_SCRIPTS_ACK);
        reply.setRunningScripts(List.of(runs));
        return reply;
    }

    private static ClusterMessage cancelAck(boolean cancelled) {
        final var reply = new ClusterMessage();
        reply.setType(ClusterMessageType.CANCEL_SCRIPT_ACK);
        reply.setCancelledRun(cancelled);
        return reply;
    }

    @Test
    public void test_local_runs_map_the_registry() {
        final var clientId = UUID.randomUUID();
        final var run = registry.register(ScriptRunKind.CALL_PROCEDURE, "shop", "reprice", "alice", clientId);
        final var local = directory.localRuns();
        assertEquals(1, local.size());
        final var row = local.getFirst();
        assertEquals(run.runId(), row.getRunId());
        assertEquals("CALL_PROCEDURE", row.getKind());
        assertEquals("shop", row.getDatabase());
        assertEquals("reprice", row.getName());
        assertEquals("alice", row.getUsername());
        assertEquals(run.startedAt(), row.getStartedAt());
    }

    @Test
    public void test_lists_local_runs_when_clustering_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        withOtherMember();
        final var pool = poolThrows();
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        final var rows = directory.listClusterWide();
        assertEquals(1, rows.size());
        assertEquals(run.runId(), rows.getFirst().get("runId").asJsonString().getValue());
        verify(pool, never()).request(any(), any(), anyLong());
    }

    @Test
    public void test_aggregates_rows_from_peers() throws Exception {
        withOtherMember();
        final var remoteId = UUID.randomUUID().toString();
        poolReturning(listAck(
                new RunningScript(remoteId, "TRIGGER", "shop", "audit", "definer", System.currentTimeMillis())));
        final var localRun = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        final var rows = directory.listClusterWide();
        assertEquals(2, rows.size());
        final var ids = rows.stream().map(row -> row.get("runId").asJsonString().getValue()).toList();
        assertTrue(ids.contains(localRun.runId()));
        assertTrue(ids.contains(remoteId));
    }

    @Test
    public void test_tags_each_row_with_its_node() throws Exception {
        withOtherMember();
        final var remoteId = UUID.randomUUID().toString();
        poolReturning(listAck(
                new RunningScript(remoteId, "SCHEDULE", "shop", "nightly", "definer", System.currentTimeMillis())));
        final var localRun = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        for (final var row : directory.listClusterWide()) {
            final var expected = row.get("runId").asJsonString().getValue().equals(localRun.runId())
                    ? SELF_ADDRESS
                    : OTHER_ADDRESS;
            assertEquals(expected, row.get("node").asJsonString().getValue());
        }
    }

    @Test
    public void test_skips_unreachable_peer_and_still_returns_local_rows() throws Exception {
        withOtherMember();
        poolThrows();
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        final var rows = directory.listClusterWide();
        assertEquals(1, rows.size());
        assertEquals(run.runId(), rows.getFirst().get("runId").asJsonString().getValue());
    }

    // Two runs that started at the same instant must report the same age
    @Test
    public void test_computes_age_from_a_single_now() throws Exception {
        withOtherMember();
        final var started = System.currentTimeMillis() - 5_000L;
        poolReturning(listAck(
                new RunningScript(UUID.randomUUID().toString(), "TRIGGER", "shop", "audit", "definer", started),
                new RunningScript(UUID.randomUUID().toString(), "TRIGGER", "shop", "audit", "definer", started)));
        final var rows = directory.listClusterWide();
        assertEquals(2, rows.size());
        final var first = rows.getFirst().get("ageMs").asJsonNumber().getValue().longValue();
        final var second = rows.getLast().get("ageMs").asJsonNumber().getValue().longValue();
        assertEquals(first, second);
        assertTrue(first >= 5_000L);
    }

    @Test
    public void test_reports_a_null_name_for_an_ad_hoc_run() {
        registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        final var row = directory.listClusterWide().getFirst();
        assertEquals("RUN_SCRIPT", row.get("kind").asJsonString().getValue());
        assertNull(row.get("name").asJsonString().getValue());
    }

    @Test
    public void test_cancel_prefers_local_and_skips_broadcast() throws Exception {
        withOtherMember();
        final var pool = poolReturning(cancelAck(true));
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        assertTrue(directory.cancelClusterWide(run.runId()));
        assertTrue(run.isCancelled());
        verify(pool, never()).request(any(), any(), anyLong());
    }

    @Test
    public void test_cancel_broadcasts_when_not_local() throws Exception {
        withOtherMember();
        final var pool = poolReturning(cancelAck(true));
        assertTrue(directory.cancelClusterWide(UUID.randomUUID().toString()));
        verify(pool).request(any(), any(), anyLong());
    }

    @Test
    public void test_cancel_returns_false_when_no_node_has_the_run() throws Exception {
        withOtherMember();
        poolReturning(cancelAck(false));
        assertFalse(directory.cancelClusterWide(UUID.randomUUID().toString()));
    }

    @Test
    public void test_cancel_returns_false_when_the_peer_is_unreachable() throws Exception {
        withOtherMember();
        poolThrows();
        assertFalse(directory.cancelClusterWide(UUID.randomUUID().toString()));
    }

    // Clustering off means no gossip at all, not a broadcast to an empty member set
    @Test
    public void test_cancel_does_not_gossip_when_clustering_is_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        withOtherMember();
        final var pool = poolReturning(cancelAck(true));
        assertFalse(directory.cancelClusterWide(UUID.randomUUID().toString()));
        verify(pool, never()).request(any(), any(), anyLong());
    }

    // startedAt is a long field on the wire class, so it must survive the EJson round trip as a long
    @Test
    public void test_normalises_boxed_started_at_from_the_wire() {
        final var eJson = IocContainer.get(EJson.class);
        final var started = System.currentTimeMillis();
        final var message = listAck(new RunningScript("r1", "TRIGGER", "shop", "audit", "definer", started));
        final var parsed = eJson.fromJson(eJson.toJson(message), ClusterMessage.class);
        assertEquals(started, parsed.getRunningScripts().getFirst().getStartedAt());
        assertEquals("audit", parsed.getRunningScripts().getFirst().getName());
    }
}
