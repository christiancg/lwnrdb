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

    // A node's own identity is only set when it joins, so every caller of peers() may reach it before
    // that: a sweep firing pre-join must see an empty peer set, not a NullPointerException.
    @Test
    public void test_peers_tolerates_a_null_self_before_this_node_joined() {
        final var view = new MembershipView(List.of(node("a", NodeState.ALIVE), node("b", NodeState.DEAD)));

        assertEquals(List.of("a"), view.peers(null).stream().map(NodeInfo::getNodeId).toList());
    }
}
