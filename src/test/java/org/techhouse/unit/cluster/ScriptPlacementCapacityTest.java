package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ScriptPlacement;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

/**
 * Placement compares load against {@code maxConcurrentScripts}, not absolute load: with a cap in play a node
 * running 3/4 is nearly full while one running 6/32 is idle, so the absolute comparison would send work to
 * the saturated node.
 */
public class ScriptPlacementCapacityTest {
    private static final long EPOCH = 42L;
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();
    private ScriptedRandom scriptedRandom;
    private ScriptPlacement placement;
    private boolean origEnabled;
    private boolean origRouting;
    private long origEpoch;

    private static NodeInfo node(String id, int port, int scriptLoad, int scriptCapacity) {
        final var node = new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad, scriptCapacity);
        node.setAdminEpoch(EPOCH);
        return node;
    }

    private void membership(NodeInfo self, NodeInfo... others) throws Exception {
        final var members = new LinkedHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        for (final var other : others) {
            members.put(other.getNodeId(), other);
        }
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    @BeforeEach
    public void setUp() throws Exception {
        scriptedRandom = new ScriptedRandom();
        placement = new ScriptPlacement(scriptedRandom);
        origEnabled = config.isClusterEnabled();
        origRouting = config.isScriptRoutingEnabled();
        origEpoch = adminEpoch.current();
        TestUtils.setPrivateField(adminEpoch, "epoch", EPOCH);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origRouting);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(adminEpoch, "epoch", origEpoch);
    }

    @Test
    public void test_prefers_the_lower_load_ratio() throws Exception {
        membership(node("a-self", 1, 99, 100), node("b", 2, 3, 4), node("c", 3, 6, 32));
        scriptedRandom.give();
        final var chosen = placement.choose();
        assertNotNull(chosen);
        assertEquals("c", chosen.getNodeId(), "6/32 is idler than 3/4, despite the higher absolute load");
    }

    // A node reporting capacity 0 is uncapped, or too old to gossip the field: there is no denominator to
    // compare against, so the pair falls back to absolute load.
    @Test
    public void test_falls_back_to_absolute_load_when_capacity_is_unknown() throws Exception {
        membership(node("a-self", 1, 99, 100), node("b", 2, 3, 0), node("c", 3, 6, 32));
        scriptedRandom.give();
        assertEquals("b", placement.choose().getNodeId());
    }

    // A saturated target could only answer 503-6, so forwarding to it would waste a round trip.
    @Test
    public void test_skips_a_saturated_target() throws Exception {
        membership(node("a-self", 1, 99, 100), node("b", 2, 4, 4), node("c", 3, 30, 32));
        scriptedRandom.give();
        assertEquals("c", placement.choose().getNodeId());
    }

    // Two nodes at the same ratio still resolve deterministically, so two edges sampling the same pair agree.
    @Test
    public void test_equal_ratios_break_on_node_id() throws Exception {
        membership(node("a-self", 1, 99, 100), node("c", 2, 2, 4), node("b", 3, 8, 16));
        scriptedRandom.give();
        assertEquals("b", placement.choose().getNodeId());
    }

    // Both full: neither is preferable, so the tie-break decides rather than the ratio.
    @Test
    public void test_two_saturated_samples_still_choose_one() throws Exception {
        membership(node("a-self", 1, 99, 100), node("c", 2, 4, 4), node("b", 3, 32, 32));
        scriptedRandom.give();
        assertEquals("b", placement.choose().getNodeId());
    }

    private static final class ScriptedRandom implements RandomGenerator {
        private int[] values = new int[0];
        private int index;

        private void give() {
            values = new int[]{1, 1};
            index = 0;
        }

        @Override
        public int nextInt(int bound) {
            return values[index++];
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }
}
