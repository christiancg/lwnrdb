package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ClusterConnectionHandlerFaultTest extends ClusterConnectionHandlerTestBase {
    private static final long DROPPED_REPLY_WAIT_MS = 500L;
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    @BeforeEach
    public void enableFaults() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
    }

    @AfterEach
    public void disableFaults() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
    }

    @Test
    public void test_a_dropped_reply_writes_nothing_and_the_next_reply_is_written() throws Exception {
        final var address = cluster.serverAddress();
        faultInjector.arm(FaultPoint.PEER_REPLY, FaultAction.DROP, "GOSSIP", 0);

        assertThrows(Exception.class,
                () -> pool.request(address, envelope(ClusterMessageType.GOSSIP), DROPPED_REPLY_WAIT_MS));
        assertTrue(faultInjector.awaitReached(FaultPoint.PEER_REPLY, 0));

        final var ack = pool.request(address, envelope(ClusterMessageType.GOSSIP), SHORT_ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.GOSSIP_ACK, ack.getType());
    }
}
