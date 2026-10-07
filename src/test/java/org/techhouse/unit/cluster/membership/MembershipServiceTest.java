package org.techhouse.unit.cluster.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;

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
        final var registry = IocContainer.get(ScriptRunRegistry.class);
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        final var first = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "u", null);
        final var second = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "u", null);
        try {
            service.gossipTick();
            assertEquals(2, service.getSelf().getScriptLoad());
        } finally {
            registry.unregister(first.runId());
            registry.unregister(second.runId());
        }
        service.gossipTick();
        assertEquals(0, service.getSelf().getScriptLoad());
    }

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
    @Test
    public void test_a_join_during_a_slow_notification_is_delivered_last() throws Exception {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        final var views = Collections.synchronizedList(new ArrayList<MembershipView>());
        final var firstCall = new AtomicBoolean(true);
        final var inFirstCall = new CountDownLatch(1);
        final var releaseFirstCall = new CountDownLatch(1);
        service.addListener(view -> {
            if (firstCall.getAndSet(false)) {
                inFirstCall.countDown();
                awaitQuietly(releaseFirstCall);
            }
            views.add(view);
        });

        final var slow = Thread.ofPlatform().start(() -> service.handleJoin(gossip(node("b", 1L, 0L), null)));
        assertTrue(inFirstCall.await(5, TimeUnit.SECONDS));
        final var fast = Thread.ofPlatform().start(() -> service.handleJoin(gossip(node("c", 1L, 0L), null)));
        awaitParkedOrDone(fast);
        releaseFirstCall.countDown();
        slow.join(5000);
        fast.join(5000);

        final var last = views.getLast();
        assertNotNull(last.find("b"));
        assertNotNull(last.find("c"), "the last view a listener applies must include every merged member");
    }

    @Test
    public void test_a_join_notifies_a_view_that_contains_the_joiner() {
        final var service = new MembershipService();
        service.bootstrap(node("self", 1L, 0L));
        final var captured = new AtomicReference<MembershipView>();
        service.addListener(captured::set);

        service.handleJoin(gossip(node("b", 1L, 0L), null));

        assertNotNull(captured.get().find("b"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitParkedOrDone(Thread thread) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            final var state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TERMINATED) {
                return;
            }
            thread.join(5);
        }
    }
}
