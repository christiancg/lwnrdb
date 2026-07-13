package org.techhouse.unit.cluster.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;

public class MembershipServiceTest {

    private static NodeInfo node(String id, long incarnation, long heartbeat) {
        return new NodeInfo(id, "127.0.0.1", 9990, NodeState.ALIVE, incarnation, heartbeat);
    }

    private static ClusterMessage gossip(NodeInfo sender, List<NodeInfo> members) {
        return new ClusterMessage("corr", ClusterMessageType.GOSSIP, "secret", sender, members);
    }

    @Test
    public void test_bootstrap_registers_self_and_notifies() {
        final var service = new MembershipService();
        final var captured = new AtomicReference<MembershipView>();
        service.addListener(captured::set);
        final var self = node("self", 1L, 0L);
        service.bootstrap(self);
        assertEquals(self, service.getSelf());
        assertNotNull(captured.get());
        assertEquals(1, captured.get().size());
        assertNotNull(service.membershipView().find("self"));
    }

    @Test
    public void test_handle_join_merges_sender_and_returns_members() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        final var response = service.handleJoin(gossip(node("b", 1L, 0L), null));
        assertEquals(ClusterMessageType.JOIN_RESPONSE, response.getType());
        assertEquals(2, response.getMembers().size());
        assertEquals(NodeState.ALIVE, service.membershipView().find("b").getState());
    }

    @Test
    public void test_handle_gossip_merges_sender_and_member_list() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        final var response = service.handleGossip(gossip(node("b", 1L, 1L), List.of(node("c", 1L, 1L))));
        assertEquals(ClusterMessageType.GOSSIP_ACK, response.getType());
        assertNotNull(service.membershipView().find("b"));
        assertNotNull(service.membershipView().find("c"));
    }

    @Test
    public void test_merge_ignores_stale_heartbeat() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node("b", 1L, 5L), null));
        service.handleGossip(gossip(node("b", 1L, 3L), null));
        assertEquals(5L, service.membershipView().find("b").getHeartbeat());
    }

    @Test
    public void test_merge_takes_higher_incarnation() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node("b", 1L, 9L), null));
        service.handleGossip(gossip(node("b", 2L, 1L), null));
        assertEquals(2L, service.membershipView().find("b").getIncarnation());
        assertEquals(1L, service.membershipView().find("b").getHeartbeat());
    }

    @Test
    public void test_merge_ignores_self_rumors() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node("self", 9L, 9L), null));
        assertEquals(1, service.membershipView().size());
    }

    @Test
    public void test_detect_failures_transitions_suspect_then_dead_then_recovers() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node("b", 1L, 1L), null));
        final var now = System.currentTimeMillis();

        service.detectFailures(now);
        assertEquals(NodeState.ALIVE, service.membershipView().find("b").getState());

        service.detectFailures(now + 6000L);
        assertEquals(NodeState.SUSPECT, service.membershipView().find("b").getState());

        service.detectFailures(now + 20000L);
        assertEquals(NodeState.DEAD, service.membershipView().find("b").getState());

        service.handleGossip(gossip(node("b", 1L, 2L), null));
        service.detectFailures(System.currentTimeMillis());
        assertEquals(NodeState.ALIVE, service.membershipView().find("b").getState());
    }

    @Test
    public void test_detect_failures_never_marks_self() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.detectFailures(System.currentTimeMillis() + 1_000_000L);
        assertEquals(NodeState.ALIVE, service.membershipView().find("self").getState());
        assertNull(service.membershipView().find("missing"));
        assertTrue(service.membershipView().aliveNodeIds().contains("self"));
    }
}
