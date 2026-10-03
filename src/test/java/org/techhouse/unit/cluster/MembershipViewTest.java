package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;

public class MembershipViewTest {

    private static NodeInfo node(String id, NodeState state) {
        return new NodeInfo(id, "127.0.0.1", 9990, state, 1L, 1L);
    }

    @Test
    public void test_snapshot_excludes_dead_members() throws Exception {
        final var membership = org.techhouse.ioc.IocContainer
                .get(org.techhouse.cluster.membership.MembershipService.class);
        @SuppressWarnings("unchecked")
        final var members = (java.util.Map<String, NodeInfo>) org.techhouse.test.TestUtils.getPrivateField(membership,
                "members", java.util.Map.class);
        members.clear();
        members.put("alive", node("alive", NodeState.ALIVE));
        members.put("dead", node("dead", NodeState.DEAD));
        try {
            final var method = membership.getClass().getDeclaredMethod("snapshot");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var snapshot = (List<NodeInfo>) method.invoke(membership);

            assertEquals(1, snapshot.size(), "a DEAD member must not be gossiped on to peers");
            assertEquals("alive", snapshot.getFirst().getNodeId());
        } finally {
            members.clear();
        }
    }

    @Test
    public void test_size_find_and_alive_filters() {
        final var view = new MembershipView(List.of(node("a", NodeState.ALIVE), node("b", NodeState.SUSPECT),
                node("c", NodeState.DEAD), node("d", NodeState.ALIVE)));
        assertEquals(4, view.size());
        assertEquals(2, view.aliveCount());
        assertEquals(List.of("a", "d"), view.aliveNodeIds());
        assertEquals("b", Objects.requireNonNull(view.find("b")).getNodeId());
        assertNull(view.find("missing"));
    }

    @Test
    public void test_empty_view() {
        final var view = new MembershipView(List.of());
        assertEquals(0, view.size());
        assertEquals(0, view.aliveCount());
        assertEquals(List.of(), view.aliveNodeIds());
    }
    @Test
    public void test_peers_excludes_self_and_dead_members() {
        final var self = node("self", NodeState.ALIVE);
        final var view = new MembershipView(List.of(self, node("peer", NodeState.ALIVE),
                node("suspect", NodeState.SUSPECT), node("gone", NodeState.DEAD)));

        assertEquals(List.of("peer"), view.peers(self).stream().map(NodeInfo::getNodeId).toList());
    }

    @Test
    public void test_peers_tolerates_a_null_self_before_this_node_joined() {
        final var view = new MembershipView(List.of(node("a", NodeState.ALIVE), node("b", NodeState.DEAD)));

        assertEquals(List.of("a"), view.peers(null).stream().map(NodeInfo::getNodeId).toList());
    }
}
