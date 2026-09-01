package org.techhouse.unit.cluster.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptLoad;

public class MembershipServiceTest {

    private static NodeInfo node(String id, long incarnation, long heartbeat) {
        return new NodeInfo(id, "127.0.0.1", 9990, NodeState.ALIVE, incarnation, heartbeat);
    }

    private static NodeInfo node(long heartbeat, int scriptLoad) {
        return new NodeInfo("b", "127.0.0.1", 9990, NodeState.ALIVE, 1L, heartbeat, scriptLoad);
    }

    private static NodeInfo node(long heartbeat, boolean adminSyncing, long adminEpoch) {
        final var node = node(heartbeat, 0);
        node.setAdminSyncing(adminSyncing);
        node.setAdminEpoch(adminEpoch);
        return node;
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
        assertEquals(NodeState.ALIVE, Objects.requireNonNull(service.membershipView().find("b")).getState());
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
        assertEquals(5L, Objects.requireNonNull(service.membershipView().find("b")).getHeartbeat());
    }

    @Test
    public void test_merge_takes_higher_incarnation() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node("b", 1L, 9L), null));
        service.handleGossip(gossip(node("b", 2L, 1L), null));
        assertEquals(2L, Objects.requireNonNull(service.membershipView().find("b")).getIncarnation());
        assertEquals(1L, Objects.requireNonNull(service.membershipView().find("b")).getHeartbeat());
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
        assertEquals(NodeState.ALIVE, Objects.requireNonNull(service.membershipView().find("b")).getState());

        service.detectFailures(now + 6000L);
        assertEquals(NodeState.SUSPECT, Objects.requireNonNull(service.membershipView().find("b")).getState());

        service.detectFailures(now + 20000L);
        assertEquals(NodeState.DEAD, Objects.requireNonNull(service.membershipView().find("b")).getState());

        service.handleGossip(gossip(node("b", 1L, 2L), null));
        service.detectFailures(System.currentTimeMillis());
        assertEquals(NodeState.ALIVE, Objects.requireNonNull(service.membershipView().find("b")).getState());
    }

    @Test
    public void test_gossip_carries_the_current_script_load() {
        final var scriptLoad = IocContainer.get(ScriptLoad.class);
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        scriptLoad.enter();
        scriptLoad.enter();
        try {
            service.gossipTick();
            assertEquals(2, service.getSelf().getScriptLoad());
        } finally {
            scriptLoad.exit();
            scriptLoad.exit();
        }
        service.gossipTick();
        assertEquals(0, service.getSelf().getScriptLoad());
    }

    // Peers filter placement on these two, so they must ride the same gossip round as the heartbeat.
    @Test
    public void test_gossip_carries_the_admin_catch_up_state() throws Exception {
        final var adminEpoch = IocContainer.get(AdminEpoch.class);
        final var original = adminEpoch.current();
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        try {
            org.techhouse.test.TestUtils.setPrivateField(adminEpoch, "epoch", 11L);
            service.setAdminSyncing(true);
            service.gossipTick();
            assertTrue(service.getSelf().isAdminSyncing());
            assertEquals(11L, service.getSelf().getAdminEpoch());

            service.setAdminSyncing(false);
            service.gossipTick();
            assertFalse(service.getSelf().isAdminSyncing());
        } finally {
            org.techhouse.test.TestUtils.setPrivateField(adminEpoch, "epoch", original);
        }
    }

    @Test
    public void test_merge_adopts_a_fresher_peers_admin_state() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node(1L, true, 4L), null));
        assertTrue(Objects.requireNonNull(service.membershipView().find("b")).isAdminSyncing());
        assertEquals(4L, Objects.requireNonNull(service.membershipView().find("b")).getAdminEpoch());
        service.handleGossip(gossip(node(2L, false, 6L), null));
        assertFalse(Objects.requireNonNull(service.membershipView().find("b")).isAdminSyncing());
        assertEquals(6L, Objects.requireNonNull(service.membershipView().find("b")).getAdminEpoch());
    }

    @Test
    public void test_merge_adopts_a_fresher_peers_script_load() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node(1L, 7), null));
        assertEquals(7, Objects.requireNonNull(service.membershipView().find("b")).getScriptLoad());
        service.handleGossip(gossip(node(2L, 2), null));
        assertEquals(2, Objects.requireNonNull(service.membershipView().find("b")).getScriptLoad());
        service.handleGossip(gossip(node(1L, 9), null));
        assertEquals(2, Objects.requireNonNull(service.membershipView().find("b")).getScriptLoad());
    }

    // The load moves every round, so adopting it must not fire the membership listeners - that would
    // rebuild the ownership ring and re-run anti-entropy on every gossip tick.
    @Test
    public void test_a_load_change_alone_does_not_notify_listeners() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.handleGossip(gossip(node(1L, 0), null));
        final var notifications = new java.util.concurrent.atomic.AtomicInteger();
        service.addListener(_ -> notifications.incrementAndGet());
        service.handleGossip(gossip(node(2L, 5), null));
        service.handleGossip(gossip(node(3L, true, 8L), null));
        assertEquals(0, notifications.get());
        assertTrue(Objects.requireNonNull(service.membershipView().find("b")).isAdminSyncing());
        assertEquals(8L, Objects.requireNonNull(service.membershipView().find("b")).getAdminEpoch());
    }

    @Test
    public void test_detect_failures_never_marks_self() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        service.detectFailures(System.currentTimeMillis() + 1_000_000L);
        assertEquals(NodeState.ALIVE, Objects.requireNonNull(service.membershipView().find("self")).getState());
        assertNull(service.membershipView().find("missing"));
        assertTrue(service.membershipView().aliveNodeIds().contains("self"));
    }
}
