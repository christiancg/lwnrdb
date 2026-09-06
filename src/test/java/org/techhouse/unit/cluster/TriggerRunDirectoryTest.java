package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.TriggerRunDirectory;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.TriggerRunRow;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.ResolveTriggerRunRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The cluster-wide half of LIST_TRIGGER_RUNS / RESOLVE_TRIGGER_RUN. The fan-out matters more here than it
 * does for running scripts: admin/trigger_runs is not replicated, so a run's record exists on exactly one
 * node and the operator is rarely connected to it.
 */
public class TriggerRunDirectoryTest {
    private final Configuration config = Configuration.getInstance();
    private final TriggerRunDirectory directory = IocContainer.get(TriggerRunDirectory.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private boolean origEnabled;
    private PeerConnectionPool origPool;

    private static NodeInfo self() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    private static NodeInfo other() {
        return new NodeInfo("other", "127.0.0.1", 5001, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerRunLogEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    public void setUp() throws Exception {
        origEnabled = config.isClusterEnabled();
        origPool = TestUtils.getPrivateField(directory, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(config, "maxEntrySize", 1_048_576L);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>(Map.of("self", self())));
        TestUtils.setPrivateField(membershipService, "self", self());
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
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

    private static ClusterMessage listAck(TriggerRunRow... runs) {
        final var reply = new ClusterMessage();
        reply.setType(ClusterMessageType.LIST_TRIGGER_RUNS_ACK);
        reply.setTriggerRuns(List.of(runs));
        return reply;
    }

    private static ClusterMessage resolveAck(boolean resolved) {
        final var reply = new ClusterMessage();
        reply.setType(ClusterMessageType.RESOLVE_TRIGGER_RUN_ACK);
        reply.setTriggerRunResolved(resolved);
        return reply;
    }

    private static String record(String triggerName) {
        final var data = new JsonObject();
        data.add("_id", new JsonString("doc1"));
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("doc1");
        entry.setData(data);
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL,
                triggerName, "proc", EventType.DELETED, false, "alice", 0, System.currentTimeMillis(), List.of(entry)));
    }

    @Test
    public void test_local_rows_map_the_recorded_runs() {
        final var runId = record("audit");
        final var rows = directory.localRuns(null);
        assertEquals(1, rows.size());
        assertEquals(runId, rows.getFirst().getRunId());
        assertEquals("PENDING", rows.getFirst().getStatus());
        assertEquals(TestGlobals.DB, rows.getFirst().getDatabase());
        assertEquals("audit", rows.getFirst().getTriggerName());
        assertEquals("DELETED", rows.getFirst().getEventType());
    }

    @Test
    public void test_lists_local_rows_when_clustering_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        withOtherMember();
        final var pool = poolThrows();
        final var runId = record("audit");

        final var rows = directory.listClusterWide(null);

        assertEquals(1, rows.size());
        assertEquals(runId, rows.getFirst().get("runId").asJsonString().getValue());
        verify(pool, never()).request(any(), any(), anyLong());
    }

    @Test
    public void test_aggregates_rows_from_peers() throws Exception {
        withOtherMember();
        poolReturning(listAck(new TriggerRunRow("remote-run", "DEAD", "shop", "orders", "audit", "proc", "UPDATED", 3,
                "Error: nope", System.currentTimeMillis(), 0L)));
        final var localRun = record("audit");

        final var rows = directory.listClusterWide(null);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> localRun.equals(row.get("runId").asJsonString().getValue())));
        final var remote = rows.stream().filter(row -> "remote-run".equals(row.get("runId").asJsonString().getValue()))
                .findFirst().orElseThrow();
        assertEquals("DEAD", remote.get("status").asJsonString().getValue());
        assertEquals("Error: nope", remote.get("lastError").asJsonString().getValue());
        assertEquals(3, remote.get("attempts").asJsonNumber().asInteger());
        assertEquals("127.0.0.1:5001", remote.get("node").asJsonString().getValue());
    }

    // An unreachable peer costs the operator its rows, not the whole listing.
    @Test
    public void test_an_unreachable_peer_is_skipped() throws Exception {
        withOtherMember();
        poolThrows();
        record("audit");

        assertEquals(1, directory.listClusterWide(null).size());
    }

    @Test
    public void test_a_status_filter_narrows_the_listing() {
        final var pendingRun = record("pending-trigger");
        final var deadRun = record("dead-trigger");
        TriggerRunLog.markAttempt(deadRun, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        final var dead = directory.listClusterWide(TriggerRunStatus.DEAD);
        assertEquals(1, dead.size());
        assertEquals(deadRun, dead.getFirst().get("runId").asJsonString().getValue());

        final var pending = directory.listClusterWide(TriggerRunStatus.PENDING);
        assertEquals(1, pending.size());
        assertEquals(pendingRun, pending.getFirst().get("runId").asJsonString().getValue());
    }

    @Test
    public void test_resolving_a_local_run_never_asks_a_peer() throws Exception {
        withOtherMember();
        final var pool = poolReturning(resolveAck(true));
        final var runId = record("audit");

        assertTrue(directory.resolveClusterWide(runId, ResolveTriggerRunRequest.DECISION_DISCARD));
        verify(pool, never()).request(any(), any(), anyLong());
    }

    @Test
    public void test_a_run_held_by_a_peer_is_resolved_there() throws Exception {
        withOtherMember();
        poolReturning(resolveAck(true));

        assertTrue(directory.resolveClusterWide("remote-run", ResolveTriggerRunRequest.DECISION_REPLAY));
    }

    @Test
    public void test_a_run_no_live_node_holds_reports_false() throws Exception {
        withOtherMember();
        poolReturning(resolveAck(false));

        assertFalse(directory.resolveClusterWide("nobody-has-this", ResolveTriggerRunRequest.DECISION_DISCARD));
    }

    @Test
    public void test_resolution_is_local_only_when_clustering_is_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        withOtherMember();
        final var pool = poolThrows();

        assertFalse(directory.resolveClusterWide("remote-run", ResolveTriggerRunRequest.DECISION_DISCARD));
        verify(pool, never()).request(any(), any(), anyLong());
    }

    // A peer that answers with an error, or with no rows at all, contributes nothing rather than breaking
    // the listing.
    @Test
    public void test_a_peer_error_reply_contributes_nothing() throws Exception {
        withOtherMember();
        final var error = new ClusterMessage();
        error.setType(ClusterMessageType.ERROR);
        error.setErrorMessage("nope");
        poolReturning(error);
        record("audit");

        assertEquals(1, directory.listClusterWide(null).size());
    }

    @Test
    public void test_a_peer_ack_without_rows_contributes_nothing() throws Exception {
        withOtherMember();
        final var empty = new ClusterMessage();
        empty.setType(ClusterMessageType.LIST_TRIGGER_RUNS_ACK);
        poolReturning(empty);
        record("audit");

        assertEquals(1, directory.listClusterWide(null).size());
    }

    @Test
    public void test_an_unreachable_peer_cannot_resolve() throws Exception {
        withOtherMember();
        poolThrows();

        assertFalse(directory.resolveClusterWide("remote-run", ResolveTriggerRunRequest.DECISION_DISCARD));
    }

    // A node with no membership identity yet still labels its own rows rather than failing the listing.
    @Test
    public void test_rows_from_a_node_without_an_identity_are_labelled_local() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        TestUtils.setPrivateField(membershipService, "self", null);
        record("audit");

        final var rows = directory.listClusterWide(null);

        assertEquals(1, rows.size());
        assertEquals("local", rows.getFirst().get("node").asJsonString().getValue());
    }

    @Test
    public void test_a_filter_travels_to_the_peer() throws Exception {
        withOtherMember();
        poolReturning(listAck(new TriggerRunRow("remote-dead", "DEAD", "shop", "orders", "audit", "proc", "UPDATED", 3,
                "Error: nope", System.currentTimeMillis(), 0L)));

        final var rows = directory.listClusterWide(TriggerRunStatus.DEAD);

        assertEquals(1, rows.size());
        assertEquals("remote-dead", rows.getFirst().get("runId").asJsonString().getValue());
    }
}
