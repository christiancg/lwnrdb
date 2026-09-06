package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
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

public class ScriptPlacementTest {
    private static final long EPOCH = 42L;
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();
    private ScriptedRandom scriptedRandom;
    private ScriptPlacement placement;
    private boolean origEnabled;
    private boolean origRouting;
    private long origEpoch;

    // The two samples are taken by index, so the members map must iterate in a known order.
    private static NodeInfo node(String id, int port, int scriptLoad, NodeState state) {
        final var node = new NodeInfo(id, "127.0.0.1", port, state, 1L, 1L, scriptLoad);
        node.setAdminEpoch(EPOCH);
        return node;
    }

    private static NodeInfo caughtUpPeer(String id, int scriptLoad) {
        return node(id, 2, scriptLoad, NodeState.ALIVE);
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

    private void samples(int first, int second) {
        scriptedRandom.give(first, second);
    }

    @BeforeEach
    public void setUp() throws Exception {
        scriptedRandom = new ScriptedRandom();
        placement = new ScriptPlacement(scriptedRandom);
        origEnabled = config.isClusterEnabled();
        origRouting = config.isScriptRoutingEnabled();
        origEpoch = adminEpoch.current();
        // Set directly rather than through bump(): the setter would persist the epoch file.
        TestUtils.setPrivateField(adminEpoch, "epoch", EPOCH);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origRouting);
        TestUtils.setPrivateField(membershipService, "members", new java.util.concurrent.ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(adminEpoch, "epoch", origEpoch);
    }

    @Test
    public void test_null_when_clustering_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        membership(node("a-self", 1, 9, NodeState.ALIVE), node("b", 2, 0, NodeState.ALIVE));
        assertNull(placement.choose());
    }

    @Test
    public void test_null_when_routing_disabled() throws Exception {
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", false);
        membership(node("a-self", 1, 9, NodeState.ALIVE), node("b", 2, 0, NodeState.ALIVE));
        assertNull(placement.choose());
    }

    @Test
    public void test_null_when_membership_has_no_self() throws Exception {
        TestUtils.setPrivateField(membershipService, "self", null);
        assertNull(placement.choose());
    }

    @Test
    public void test_null_when_self_is_the_only_live_member() throws Exception {
        membership(node("a-self", 1, 0, NodeState.ALIVE));
        assertNull(placement.choose());
    }

    @Test
    public void test_skips_suspect_and_dead_members() throws Exception {
        membership(node("a-self", 1, 9, NodeState.ALIVE), node("b", 2, 0, NodeState.SUSPECT),
                node("c", 3, 0, NodeState.DEAD));
        assertNull(placement.choose());
    }

    @Test
    public void test_picks_the_less_loaded_of_two_samples() throws Exception {
        membership(node("a-self", 1, 9, NodeState.ALIVE), node("b", 2, 7, NodeState.ALIVE),
                node("c", 3, 2, NodeState.ALIVE));
        samples(1, 1);
        final var chosen = placement.choose();
        assertNotNull(chosen);
        assertEquals("c", chosen.getNodeId());
    }

    @Test
    public void test_ties_break_on_node_id_deterministically() throws Exception {
        membership(node("a-self", 1, 9, NodeState.ALIVE), node("c", 2, 4, NodeState.ALIVE),
                node("b", 3, 4, NodeState.ALIVE));
        samples(1, 1);
        assertEquals("b", placement.choose().getNodeId());
        samples(2, 1);
        assertEquals("b", placement.choose().getNodeId());
    }

    @Test
    public void test_null_when_the_chosen_node_is_self() throws Exception {
        membership(node("a-self", 1, 0, NodeState.ALIVE), node("b", 2, 5, NodeState.ALIVE));
        samples(0, 0);
        assertNull(placement.choose());
    }

    // A second sample equal to the first is shifted past it, so the two samples are never the same node -
    // without the shift the pair below would compare b against itself and answer b.
    @Test
    public void test_never_samples_the_same_node_twice() throws Exception {
        membership(node("a-self", 1, 0, NodeState.ALIVE), node("b", 2, 8, NodeState.ALIVE),
                node("c", 3, 1, NodeState.ALIVE));
        samples(1, 1);
        assertEquals("c", placement.choose().getNodeId());
    }

    // A node that has not finished catching up on admin metadata may not know the database the script is
    // scoped to, so it is not a candidate even though it is ALIVE and idle.
    @Test
    public void test_skips_a_peer_that_is_still_admin_syncing() throws Exception {
        final var syncing = caughtUpPeer("b", 0);
        syncing.setAdminSyncing(true);
        membership(node("a-self", 1, 9, NodeState.ALIVE), syncing);
        assertNull(placement.choose());
    }

    // A peer that missed a majority-replicated DDL reports a lower epoch until anti-entropy catches it up.
    @Test
    public void test_skips_a_peer_whose_admin_epoch_is_behind() throws Exception {
        final var behind = caughtUpPeer("b", 0);
        behind.setAdminEpoch(EPOCH - 1);
        membership(node("a-self", 1, 9, NodeState.ALIVE), behind);
        assertNull(placement.choose());
    }

    @Test
    public void test_keeps_a_peer_whose_admin_epoch_is_ahead() throws Exception {
        final var ahead = caughtUpPeer("b", 0);
        ahead.setAdminEpoch(EPOCH + 5);
        membership(node("a-self", 1, 9, NodeState.ALIVE), ahead);
        samples(0, 0);
        assertEquals("b", placement.choose().getNodeId());
    }

    // Self is never filtered by its own catch-up state: it is where the script runs anyway.
    @Test
    public void test_self_stays_a_candidate_while_it_is_admin_syncing() throws Exception {
        final var self = node("a-self", 1, 0, NodeState.ALIVE);
        self.setAdminSyncing(true);
        membership(self, caughtUpPeer("b", 5));
        samples(0, 0);
        assertNull(placement.choose());
    }

    // One caught-up peer among two ineligible ones still leaves only that peer plus self to sample.
    @Test
    public void test_picks_the_only_caught_up_peer() throws Exception {
        final var syncing = caughtUpPeer("b", 0);
        syncing.setAdminSyncing(true);
        final var behind = caughtUpPeer("c", 0);
        behind.setAdminEpoch(EPOCH - 1);
        membership(node("a-self", 1, 9, NodeState.ALIVE), syncing, behind, caughtUpPeer("d", 0));
        samples(0, 0);
        assertEquals("d", placement.choose().getNodeId());
    }

    @Test
    public void test_counters_start_at_zero_and_record() {
        assertEquals(0L, placement.getForwarded());
        assertEquals(0L, placement.getForwardFallbacks());
        placement.recordForward();
        placement.recordFallback();
        placement.recordFallback();
        assertEquals(1L, placement.getForwarded());
        assertEquals(2L, placement.getForwardFallbacks());
    }

    // Hands out the sample indexes a test asked for, so a placement decision is reproducible.
    private static final class ScriptedRandom implements RandomGenerator {
        private int[] values = new int[0];
        private int index;

        private void give(int... samples) {
            values = samples;
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
