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
    public void test_script_load_travels_on_the_all_args_constructor() {
        final var node = new NodeInfo("node-1", "127.0.0.1", 9990, NodeState.ALIVE, 100L, 5L, 7);
        assertEquals(7, node.getScriptLoad());
        assertEquals(0, sample().getScriptLoad());
        node.setScriptLoad(3);
        assertEquals(3, node.getScriptLoad());
        assertTrue(node.toString().contains("scriptLoad=3"));
    }

    @Test
    public void test_telemetry_is_copied_from_a_peer_report() {
        final var incoming = new NodeInfo("node-1", "127.0.0.1", 9990, NodeState.ALIVE, 100L, 5L, 4);
        incoming.setAdminSyncing(true);
        incoming.setAdminEpoch(9L);
        final var local = sample();
        local.copyTelemetryFrom(incoming);
        assertEquals(4, local.getScriptLoad());
        assertTrue(local.isAdminSyncing());
        assertEquals(9L, local.getAdminEpoch());
        assertTrue(local.toString().contains("adminSyncing=true"));
        assertTrue(local.toString().contains("adminEpoch=9"));
    }

    @Test
    public void test_equals_hashcode_tostring() {
        final var a = sample();
        final var b = sample();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.setHeartbeat(6L);
        assertNotEquals(a, b);
        b.setHeartbeat(5L);
        b.setScriptLoad(4);
        assertNotEquals(a, b);
        assertNotEquals(a.hashCode(), b.hashCode());
        b.setScriptLoad(0);
        assertEquals(a, b);
        b.setAdminSyncing(true);
        assertNotEquals(a, b);
        b.setAdminSyncing(false);
        b.setAdminEpoch(3L);
        assertNotEquals(a, b);
        assertNotEquals(a.hashCode(), b.hashCode());
        b.setAdminEpoch(0L);
        assertEquals(a, b);
        b.setHeartbeat(6L);
        assertNotEquals(null, a);
        assertNotEquals("node-1", a);
        assertTrue(a.toString().contains("node-1"));
    }
}
