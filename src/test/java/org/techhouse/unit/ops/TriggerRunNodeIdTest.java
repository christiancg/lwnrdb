package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
