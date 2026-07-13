package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRpc;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;

public class ClusterRpcTest {

    @Test
    public void test_new_correlation_ids_are_unique() {
        final var rpc = new ClusterRpc();
        assertNotEquals(rpc.newCorrelationId(), rpc.newCorrelationId());
    }

    @Test
    public void test_register_and_complete() throws Exception {
        final var rpc = new ClusterRpc();
        final var id = rpc.newCorrelationId();
        final var future = rpc.register(id);
        assertEquals(1, rpc.pendingCount());
        final var message = new ClusterMessage(id, ClusterMessageType.GOSSIP_ACK, "s", null, null);
        assertTrue(rpc.complete(id, message));
        assertEquals(message, future.get());
        assertEquals(0, rpc.pendingCount());
    }

    @Test
    public void test_complete_unknown_correlation_returns_false() {
        final var rpc = new ClusterRpc();
        assertFalse(rpc.complete("missing", new ClusterMessage()));
    }

    @Test
    public void test_fail_completes_exceptionally() {
        final var rpc = new ClusterRpc();
        final var id = rpc.newCorrelationId();
        final var future = rpc.register(id);
        rpc.fail(id, new RuntimeException("boom"));
        final var ex = assertThrows(ExecutionException.class, future::get);
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    public void test_fail_all_completes_every_pending() {
        final var rpc = new ClusterRpc();
        final var f1 = rpc.register(rpc.newCorrelationId());
        final var f2 = rpc.register(rpc.newCorrelationId());
        rpc.failAll(new RuntimeException("closed"));
        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
        assertEquals(0, rpc.pendingCount());
    }
}
