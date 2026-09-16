package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MembershipEvictionTest {
    private final MembershipService membership = IocContainer.get(MembershipService.class);

    @SuppressWarnings("unchecked")
    private Map<String, NodeInfo> members() throws Exception {
        return TestUtils.getPrivateField(membership, "members", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastSeen() throws Exception {
        return TestUtils.getPrivateField(membership, "lastSeen", Map.class);
    }

    @BeforeEach
    public void setUp() throws Exception {
        members().clear();
        lastSeen().clear();
        TestUtils.setPrivateField(membership, "self", node("self", 9990));
    }

    @AfterEach
    public void tearDown() throws Exception {
        members().clear();
        lastSeen().clear();
    }

    private static NodeInfo node(String id, int port) {
        final var info = new NodeInfo();
        info.setNodeId(id);
        info.setHost("127.0.0.1");
        info.setPort(port);
        info.setState(NodeState.ALIVE);
        return info;
    }

    private void seedPeer(String id, int port, long lastSeenMillis) throws Exception {
        members().put(id, node(id, port));
        lastSeen().put(id, lastSeenMillis);
    }

    @Test
    public void test_a_dead_member_is_not_evicted_before_the_grace_period() throws Exception {
        final var now = System.currentTimeMillis();
        final var justDead = now - (2L * Configuration.getInstance().getDeadTimeoutMs());
        seedPeer("peer", 9991, justDead);

        membership.detectFailures(now);

        assertTrue(members().containsKey("peer"),
                "a merely dead peer must stay counted, or a transient failure shrinks the quorum");
        assertSame(NodeState.DEAD, members().get("peer").getState());
    }

    @Test
    public void test_a_long_dead_member_is_evicted() throws Exception {
        final var now = System.currentTimeMillis();
        final var longGone = now - (2L * Configuration.getInstance().getDeadEvictionMs());
        seedPeer("stale", 9992, longGone);

        membership.detectFailures(now);

        assertFalse(members().containsKey("stale"), "a node dead past the eviction grace must leave the view");
        assertFalse(lastSeen().containsKey("stale"), "its heartbeat record must go with it");
    }

    @Test
    public void test_an_alive_member_is_untouched() throws Exception {
        final var now = System.currentTimeMillis();
        seedPeer("fresh", 9993, now);

        membership.detectFailures(now);

        assertTrue(members().containsKey("fresh"));
        assertSame(NodeState.ALIVE, members().get("fresh").getState());
    }

    @Test
    public void test_self_is_never_evicted() throws Exception {
        members().put("self", node("self", 9990));
        lastSeen().put("self", 0L);

        membership.detectFailures(System.currentTimeMillis());

        assertTrue(members().containsKey("self"));
    }
}
