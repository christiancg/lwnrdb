package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MembershipPartitionHealTest {
    private final MembershipService membership = IocContainer.get(MembershipService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private PeerConnectionPool originalPool;
    private PeerConnectionPool pool;

    @SuppressWarnings("unchecked")
    private Map<String, NodeInfo> members() throws Exception {
        return TestUtils.getPrivateField(membership, "members", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastSeen() throws Exception {
        return TestUtils.getPrivateField(membership, "lastSeen", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastProbe() throws Exception {
        return TestUtils.getPrivateField(membership, "lastProbe", Map.class);
    }

    @BeforeEach
    public void setUp() throws Exception {
        originalPool = TestUtils.getPrivateField(membership, "pool", PeerConnectionPool.class);
        pool = mock(PeerConnectionPool.class);
        TestUtils.setPrivateField(membership, "pool", pool);
        members().clear();
        lastSeen().clear();
        lastProbe().clear();
        final var self = node("self", 9990, NodeState.ALIVE);
        TestUtils.setPrivateField(membership, "self", self);
        members().put("self", self);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(membership, "pool", originalPool);
        members().clear();
        lastSeen().clear();
        lastProbe().clear();
    }

    private static NodeInfo node(String id, int port, NodeState state) {
        final var info = new NodeInfo();
        info.setNodeId(id);
        info.setHost("127.0.0.1");
        info.setPort(port);
        info.setState(state);
        return info;
    }

    private void seedPeer(String id, int port, NodeState state, long silentForMillis) throws Exception {
        members().put(id, node(id, port, state));
        lastSeen().put(id, System.currentTimeMillis() - silentForMillis);
    }

    private long justPastDeadTimeout() {
        return clusterConfig.deadTimeoutMs() + 1_000;
    }

    private static ClusterMessage replyCarrying(NodeInfo... nodes) {
        final var reply = new ClusterMessage();
        reply.setMembers(List.of(nodes));
        return reply;
    }

    @Test
    public void test_a_dead_peer_is_probed() throws Exception {
        seedPeer("gone", 9991, NodeState.DEAD, justPastDeadTimeout());
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying());

        membership.gossipTick();

        verify(pool, timeout(2_000).times(1)).request(eq(new NodeAddress("127.0.0.1", 9991)), any(), anyLong());
    }

    @Test
    public void test_a_suspect_peer_is_probed() throws Exception {
        seedPeer("quiet", 9992, NodeState.SUSPECT, clusterConfig.suspectTimeoutMs() + 500);
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying());

        membership.gossipTick();

        verify(pool, timeout(2_000).times(1)).request(eq(new NodeAddress("127.0.0.1", 9992)), any(), anyLong());
    }

    @Test
    public void test_a_dead_peer_is_not_probed_again_inside_the_interval() throws Exception {
        seedPeer("gone", 9991, NodeState.DEAD, justPastDeadTimeout());
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying());

        membership.gossipTick();
        verify(pool, timeout(2_000).times(1)).request(any(), any(), anyLong());
        membership.gossipTick();

        verify(pool, after(300).times(1)).request(any(), any(), anyLong());
    }

    @Test
    public void test_a_dead_peer_is_probed_again_once_the_interval_has_passed() throws Exception {
        seedPeer("gone", 9991, NodeState.DEAD, justPastDeadTimeout());
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying());
        membership.gossipTick();
        verify(pool, timeout(2_000).times(1)).request(any(), any(), anyLong());

        lastProbe().put("gone", System.currentTimeMillis() - clusterConfig.deadProbeIntervalMs() - 1);
        membership.gossipTick();

        verify(pool, timeout(2_000).times(2)).request(any(), any(), anyLong());
    }

    @Test
    public void test_a_probe_reply_restores_the_peer_and_forgets_the_probe() throws Exception {
        seedPeer("healed", 9993, NodeState.DEAD, justPastDeadTimeout());
        final var fresher = node("healed", 9993, NodeState.ALIVE);
        fresher.setHeartbeat(500);
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying(fresher));

        membership.gossipTick();

        final var deadline = System.currentTimeMillis() + 3_000;
        while (members().get("healed").getState() != NodeState.ALIVE && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        assertEquals(NodeState.ALIVE, members().get("healed").getState());
        assertFalse(lastProbe().containsKey("healed"));
    }

    @Test
    public void test_a_probe_from_a_peer_we_think_is_dead_restores_it() throws Exception {
        seedPeer("prober", 9994, NodeState.DEAD, justPastDeadTimeout());
        final var sender = node("prober", 9994, NodeState.ALIVE);
        sender.setHeartbeat(900);
        final var probe = replyCarrying();
        probe.setSender(sender);

        membership.handleGossip(probe);

        assertEquals(NodeState.ALIVE, members().get("prober").getState());
    }

    @Test
    public void test_a_probe_failure_does_not_refresh_the_peer() throws Exception {
        seedPeer("black-hole", 9995, NodeState.DEAD, justPastDeadTimeout());
        final var seenBefore = lastSeen().get("black-hole");
        when(pool.request(any(), any(), anyLong())).thenThrow(new java.io.IOException("unreachable"));

        membership.gossipTick();
        verify(pool, timeout(2_000).times(1)).request(any(), any(), anyLong());

        assertEquals(seenBefore, lastSeen().get("black-hole"));
        assertEquals(NodeState.DEAD, members().get("black-hole").getState());
    }

    @Test
    public void test_an_evicted_peer_is_never_probed() throws Exception {
        seedPeer("evicted", 9996, NodeState.DEAD, clusterConfig.deadEvictionMs() + 1_000);
        membership.detectFailures(System.currentTimeMillis());

        membership.gossipTick();

        verify(pool, after(300).never()).request(any(), any(), anyLong());
        assertFalse(members().containsKey("evicted"));
    }

    @Test
    public void test_an_alive_peer_is_gossiped_with_and_not_counted_as_a_probe() throws Exception {
        seedPeer("steady", 9997, NodeState.ALIVE, 100);
        when(pool.request(any(), any(), anyLong())).thenReturn(replyCarrying());

        membership.gossipTick();

        verify(pool, timeout(2_000).times(1)).request(eq(new NodeAddress("127.0.0.1", 9997)), any(), anyLong());
        verify(pool, never()).request(eq(new NodeAddress("127.0.0.1", 9990)), any(), anyLong());
        assertTrue(lastProbe().isEmpty());
    }
}
