package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.test.TestUtils;

public class TriggerRunNodeIdTest {
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final Configuration configuration = Configuration.getInstance();
    private boolean originalClusterEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        originalClusterEnabled = configuration.isClusterEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", originalClusterEnabled);
        TestUtils.setPrivateField(configuration, "nodeId", "");
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_clustered_node_stamps_its_real_id_before_membership_starts() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        TestUtils.setPrivateField(membershipService, "self", null);

        final var stamped = TriggerRunLog.currentNodeId();

        assertNotEquals(Globals.STANDALONE_NODE_ID, stamped,
                "startup recovery records runs before membership starts, and recoverLocal then filters by the"
                        + " real node id, so a placeholder means those runs never fire");
        assertEquals(membershipService.resolveNodeId(), stamped, "the id must be the one this node will gossip under");
    }

    @Test
    public void test_a_standalone_node_still_stamps_local() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(membershipService, "self", null);

        assertEquals(Globals.STANDALONE_NODE_ID, TriggerRunLog.currentNodeId());
    }

    @Test
    public void test_a_clustered_node_owns_its_id_and_the_runs_it_stamped_while_standalone() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        TestUtils.setPrivateField(membershipService, "self", null);

        final var own = TriggerRunLog.ownNodeIds();

        assertTrue(own.contains(membershipService.resolveNodeId()));
        assertTrue(own.contains(Globals.STANDALONE_NODE_ID),
                "admin/trigger_runs is never replicated, so a run stamped local was written by this node before"
                        + " clusterEnabled was switched on");
    }

    @Test
    public void test_a_standalone_node_owns_the_runs_it_stamped_while_clustered() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        TestUtils.setPrivateField(membershipService, "self", null);
        final var clusteredId = membershipService.resolveNodeId();
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);

        final var own = TriggerRunLog.ownNodeIds();

        assertTrue(own.contains(clusteredId), "the uuid persisted in cluster/node.id is this node's former id");
        assertTrue(own.contains(Globals.STANDALONE_NODE_ID));
    }

    @Test
    public void test_a_configured_node_id_is_owned_even_after_the_cluster_is_switched_off() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(configuration, "nodeId", "  node-a  ");
        TestUtils.setPrivateField(membershipService, "self", null);

        assertTrue(TriggerRunLog.ownNodeIds().contains("node-a"));
    }

    @Test
    public void test_an_id_this_node_never_ran_under_is_not_owned() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(membershipService, "self", null);

        assertFalse(TriggerRunLog.ownNodeIds().contains("another-node"));
    }
}
