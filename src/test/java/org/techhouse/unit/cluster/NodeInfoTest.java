package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;

public class NodeInfoTest {

    private static NodeInfo sample() {
        return new NodeInfo("node-1", "127.0.0.1", 9990, NodeState.ALIVE, 100L, 5L);
    }

    @Test
    public void test_getters_and_address() {
        final var node = sample();
        assertEquals("node-1", node.getNodeId());
        assertEquals("127.0.0.1", node.getHost());
        assertEquals(9990, node.getPort());
        assertEquals(NodeState.ALIVE, node.getState());
        assertEquals(100L, node.getIncarnation());
        assertEquals(5L, node.getHeartbeat());
        assertEquals("127.0.0.1:9990", node.address().toString());
    }

    @Test
    public void test_setters() {
        final var node = new NodeInfo();
        node.setNodeId("n");
        node.setHost("h");
        node.setPort(1);
        node.setState(NodeState.DEAD);
        node.setIncarnation(2L);
        node.setHeartbeat(3L);
        assertEquals("n", node.getNodeId());
        assertEquals("h", node.getHost());
        assertEquals(1, node.getPort());
        assertEquals(NodeState.DEAD, node.getState());
        assertEquals(2L, node.getIncarnation());
        assertEquals(3L, node.getHeartbeat());
    }

    @Test
    public void test_equals_hashcode_tostring() {
        final var a = sample();
        final var b = sample();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.setHeartbeat(6L);
        assertNotEquals(a, b);
        assertNotEquals(a, null);
        assertNotEquals(a, "node-1");
        assertTrue(a.toString().contains("node-1"));
    }
}
