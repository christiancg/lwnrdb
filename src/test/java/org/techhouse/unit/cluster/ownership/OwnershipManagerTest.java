package org.techhouse.unit.cluster.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class OwnershipManagerTest {

    private static NodeInfo node(String id, NodeState state) {
        return new NodeInfo(id, "127.0.0.1", 9990, state, 1L, 1L);
    }

    @AfterEach
    public void restoreExpectedSize() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterExpectedSize", 1);
    }

    @Test
    public void test_no_members_owns_nothing() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        assertNull(manager.ownerFor("db", "coll"));
        assertFalse(manager.isOwner("db", "coll"));
    }

    @Test
    public void test_single_node_is_always_owner() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        manager.onMembershipChanged(new MembershipView(List.of(node("self", NodeState.ALIVE))));
        for (var i = 0; i < 50; i++) {
            assertTrue(manager.isOwner("db", "coll-" + i));
        }
    }

    @Test
    public void test_dead_nodes_are_excluded_from_ownership() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        manager.onMembershipChanged(
                new MembershipView(List.of(node("self", NodeState.ALIVE), node("other", NodeState.DEAD))));
        for (var i = 0; i < 50; i++) {
            assertTrue(manager.isOwner("db", "coll-" + i));
        }
    }

    @Test
    public void test_ownership_split_between_two_alive_nodes() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        manager.onMembershipChanged(
                new MembershipView(List.of(node("self", NodeState.ALIVE), node("other", NodeState.ALIVE))));
        var ownedBySelf = false;
        var ownedByOther = false;
        for (var i = 0; i < 200; i++) {
            if (manager.isOwner("db", "coll-" + i)) {
                ownedBySelf = true;
            } else {
                ownedByOther = true;
            }
        }
        assertTrue(ownedBySelf);
        assertTrue(ownedByOther);
    }

    @Test
    public void test_quorum_from_current_membership() {
        final var manager = new OwnershipManager();
        manager.onMembershipChanged(new MembershipView(
                List.of(node("a", NodeState.ALIVE), node("b", NodeState.ALIVE), node("c", NodeState.DEAD))));
        assertTrue(manager.hasQuorum());
        manager.onMembershipChanged(new MembershipView(
                List.of(node("a", NodeState.ALIVE), node("b", NodeState.DEAD), node("c", NodeState.DEAD))));
        assertFalse(manager.hasQuorum());
    }

    @Test
    public void test_quorum_uses_expected_size_when_larger() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterExpectedSize", 5);
        final var manager = new OwnershipManager();
        manager.onMembershipChanged(new MembershipView(
                List.of(node("a", NodeState.ALIVE), node("b", NodeState.ALIVE), node("c", NodeState.ALIVE))));
        assertTrue(manager.hasQuorum());
        manager.onMembershipChanged(
                new MembershipView(List.of(node("a", NodeState.ALIVE), node("b", NodeState.ALIVE))));
        assertFalse(manager.hasQuorum());
    }

    @Test
    public void test_single_node_is_admin_coordinator() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        manager.onMembershipChanged(new MembershipView(List.of(node("self", NodeState.ALIVE))));
        assertTrue(manager.isAdminCoordinator());
        assertNotNull(manager.adminCoordinatorAddress());
    }

    @Test
    public void test_admin_coordinator_is_deterministic_and_single() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        manager.onMembershipChanged(
                new MembershipView(List.of(node("self", NodeState.ALIVE), node("other", NodeState.ALIVE))));
        final var other = new OwnershipManager();
        other.setSelfNodeId("other");
        other.onMembershipChanged(
                new MembershipView(List.of(node("self", NodeState.ALIVE), node("other", NodeState.ALIVE))));
        // Exactly one of the two nodes considers itself the coordinator.
        assertTrue(manager.isAdminCoordinator() ^ other.isAdminCoordinator());
    }

    @Test
    public void test_no_members_has_no_admin_coordinator() {
        final var manager = new OwnershipManager();
        manager.setSelfNodeId("self");
        assertFalse(manager.isAdminCoordinator());
        assertNull(manager.adminCoordinatorAddress());
    }
}
