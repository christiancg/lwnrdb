package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class OwnershipReCheckTest {
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private boolean originalEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        originalEnabled = Configuration.getInstance().isClusterEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", originalEnabled);
        ownership.onMembershipChanged(new MembershipView(List.of()));
    }

    private static NodeInfo node(String id, int port) {
        final var info = new NodeInfo();
        info.setNodeId(id);
        info.setHost("127.0.0.1");
        info.setPort(port);
        info.setState(NodeState.ALIVE);
        return info;
    }

    @Test
    public void test_ownership_follows_the_rebuilt_ring() {
        ownership.setSelfNodeId("a");
        ownership.onMembershipChanged(new MembershipView(List.of(node("a", 9990))));
        assertTrue(ownership.isOwner("db", "coll"), "a lone node owns everything");

        ownership.setSelfNodeId("absent");
        assertFalse(ownership.isOwner("db", "coll"), "a node outside the ring owns nothing");
    }

    @Test
    public void test_losing_ownership_is_visible_immediately_to_a_re_check() {
        ownership.setSelfNodeId("a");
        ownership.onMembershipChanged(new MembershipView(List.of(node("a", 9990))));
        assertTrue(ownership.isOwner("db", "coll"));

        ownership.onMembershipChanged(new MembershipView(List.of(node("b", 9991))));
        assertFalse(ownership.isOwner("db", "coll"),
                "a re-check after the ring rebuild must see the loss without any generation token");
    }

    @Test
    public void test_an_empty_ring_has_no_owner() {
        ownership.setSelfNodeId("a");
        ownership.onMembershipChanged(new MembershipView(List.of()));
        assertNull(ownership.ownerFor("db", "coll"));
        assertFalse(ownership.isOwner("db", "coll"));
    }
}
