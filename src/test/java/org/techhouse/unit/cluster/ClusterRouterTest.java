package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterTest {
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() {
        origEnabled = config.isClusterEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
    }

    private SaveRequest save() {
        return new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_null_when_clustering_disabled() {
        assertNull(router.forward(save(), "{}", false, null));
    }

    @Test
    public void test_null_when_transaction_active() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(router.forward(save(), "{}", true, null));
    }

    @Test
    public void test_null_for_non_routable_operation() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(router.forward(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL), "{}", false, null));
    }

    @Test
    public void test_null_for_admin_database() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(router.forward(new SaveRequest(Globals.ADMIN_DB_NAME, "databases"), "{}", false, null));
    }

    @Test
    public void test_null_when_this_node_is_owner() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node())));
        assertNull(router.forward(save(), "{}", false, null));
    }

    @Test
    public void test_null_when_ownership_not_established() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of()));
        assertNull(router.forward(save(), "{}", false, null));
    }
}
