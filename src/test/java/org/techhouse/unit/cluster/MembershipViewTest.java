package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
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
        assertEquals("b", view.find("b").getNodeId());
        assertNull(view.find("missing"));
    }

    @Test
    public void test_empty_view() {
        final var view = new MembershipView(List.of());
        assertEquals(0, view.size());
        assertEquals(0, view.aliveCount());
        assertEquals(List.of(), view.aliveNodeIds());
    }
}
