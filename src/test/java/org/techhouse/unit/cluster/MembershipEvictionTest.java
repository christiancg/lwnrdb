package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MembershipEvictionTest {
    private final MembershipService membership = IocContainer.get(MembershipService.class);

    @SuppressWarnings("unchecked")
    private Map<String, NodeInfo> members() throws Exception {
        return TestUtils.getPrivateField(membership, "members", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastSeen() throws Exception {
        return TestUtils.getPrivateField(membership, "lastSeen", Map.class);
    }

    @BeforeEach
    public void setUp() throws Exception {
        members().clear();
        lastSeen().clear();
        TestUtils.setPrivateField(membership, "self", node("self", 9990));
    }

    @AfterEach
    public void tearDown() throws Exception {
        members().clear();
        lastSeen().clear();
    }

    private static NodeInfo node(String id, int port) {
        final var info = new NodeInfo();
        info.setNodeId(id);
        info.setHost("127.0.0.1");
        info.setPort(port);
        info.setState(NodeState.ALIVE);
        return info;
    }

    private void seedPeer(String id, int port, long lastSeenMillis) throws Exception {
        members().put(id, node(id, port));
        lastSeen().put(id, lastSeenMillis);
    }

    private static ClusterMessage gossipCarrying(NodeInfo... members) {
        final var message = new ClusterMessage();
        message.setMembers(java.util.List.of(members));
        return message;
    }

    @Test
    public void test_an_evicted_node_is_not_resurrected_by_a_stale_gossip_snapshot() throws Exception {
        final var now = System.currentTimeMillis();
        final var evictionWindow = IocContainer.get(org.techhouse.cluster.ClusterConfig.class).deadEvictionMs();
        seedPeer("gone", 9991, now - evictionWindow - 1000);

        membership.detectFailures(now);
        assertFalse(members().containsKey("gone"), "the peer must have been evicted first");

        membership.handleGossip(gossipCarrying(node("gone", 9991)));

        assertFalse(members().containsKey("gone"),
                "a peer that has not evicted it yet must not be able to relay a corpse back as ALIVE");
    }

    @Test
    public void test_merge_honours_the_incoming_state_for_an_unknown_node() throws Exception {
        final var suspect = node("suspect", 9992);
        suspect.setState(NodeState.SUSPECT);

        membership.handleGossip(gossipCarrying(suspect));

        assertEquals(NodeState.SUSPECT, members().get("suspect").getState(),
                "a node first learned about while SUSPECT must not be admitted as ALIVE");
    }

    @Test
    public void test_a_genuinely_new_node_is_still_admitted_as_alive() throws Exception {
        membership.handleGossip(gossipCarrying(node("fresh", 9993)));

        assertTrue(members().containsKey("fresh"));
        assertEquals(NodeState.ALIVE, members().get("fresh").getState());
    }

    @Test
    public void test_a_restarted_node_with_a_higher_incarnation_is_readmitted() throws Exception {
        final var now = System.currentTimeMillis();
        final var evictionWindow = IocContainer.get(org.techhouse.cluster.ClusterConfig.class).deadEvictionMs();
        seedPeer("restarted", 9994, now - evictionWindow - 1000);
        membership.detectFailures(now);
        assertFalse(members().containsKey("restarted"));

        final var rebooted = node("restarted", 9994);
        rebooted.setIncarnation(System.currentTimeMillis() + 10_000);
        membership.handleGossip(gossipCarrying(rebooted));

        assertTrue(members().containsKey("restarted"),
                "a genuine restart bumps the incarnation and must be readmitted");
    }

    @Test
    public void test_a_dead_member_is_not_evicted_before_the_grace_period() throws Exception {
        final var now = System.currentTimeMillis();
        final var justDead = now - (2L * Configuration.getInstance().getDeadTimeoutMs());
        seedPeer("peer", 9991, justDead);

        membership.detectFailures(now);

        assertTrue(members().containsKey("peer"),
                "a merely dead peer must stay counted, or a transient failure shrinks the quorum");
        assertSame(NodeState.DEAD, members().get("peer").getState());
    }

    @Test
    public void test_a_long_dead_member_is_evicted() throws Exception {
        final var now = System.currentTimeMillis();
        final var longGone = now - (2L * Configuration.getInstance().getDeadEvictionMs());
        seedPeer("stale", 9992, longGone);

        membership.detectFailures(now);

        assertFalse(members().containsKey("stale"), "a node dead past the eviction grace must leave the view");
        assertFalse(lastSeen().containsKey("stale"), "its heartbeat record must go with it");
    }

    @Test
    public void test_an_alive_member_is_untouched() throws Exception {
        final var now = System.currentTimeMillis();
        seedPeer("fresh", 9993, now);

        membership.detectFailures(now);

        assertTrue(members().containsKey("fresh"));
        assertSame(NodeState.ALIVE, members().get("fresh").getState());
    }

    @Test
    public void test_self_is_never_evicted() throws Exception {
        members().put("self", node("self", 9990));
        lastSeen().put("self", 0L);

        membership.detectFailures(System.currentTimeMillis());

        assertTrue(members().containsKey("self"));
    }
}
