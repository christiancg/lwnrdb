package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ScriptPlacement;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterTest {
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private boolean origRouting;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() {
        origEnabled = config.isClusterEnabled();
        origRouting = config.isScriptRoutingEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origRouting);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    // An unreachable peer at port 1, caught up on admin metadata and with no script load at all, so
    // placement always prefers it over self.
    private void routableMembership() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        final var self = new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L, 9);
        final var other = new NodeInfo("other", "127.0.0.1", 1, NodeState.ALIVE, 1L, 1L, 0);
        other.setAdminEpoch(IocContainer.get(AdminEpoch.class).current());
        final var members = new LinkedHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        members.put(other.getNodeId(), other);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    private RunScriptRequest runScript() {
        return new RunScriptRequest(TestGlobals.DB, "return 1;", null);
    }

    private SaveRequest save() {
        return new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_null_when_clustering_disabled() {
        assertNull(router.forward(save(), "{}", false, null, null));
    }

    @Test
    public void test_transaction_write_without_owner_runs_local() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(router.forward(save(), "{}", true, null, null));
    }

    @Test
    public void test_null_for_non_routable_operation() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(
                router.forward(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL), "{}", false, null, null));
    }

    @Test
    public void test_null_for_admin_database() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        assertNull(router.forward(new SaveRequest(Globals.ADMIN_DB_NAME, "databases"), "{}", false, null, null));
    }

    @Test
    public void test_null_when_this_node_is_owner() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node())));
        assertNull(router.forward(save(), "{}", false, null, null));
    }

    @Test
    public void test_run_script_is_not_routed_when_routing_is_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", false);
        assertNull(router.forward(runScript(), "{}", false, null, null));
    }

    // A forward that cannot be delivered runs the script here instead of failing the request.
    @Test
    public void test_run_script_falls_back_to_local_when_the_target_is_unreachable() throws Exception {
        routableMembership();
        final var fallbacksBefore = scriptPlacement.getForwardFallbacks();
        final var forwardedBefore = scriptPlacement.getForwarded();
        assertNull(router.forward(runScript(), "{}", false, "someuser", null));
        assertEquals(fallbacksBefore + 1, scriptPlacement.getForwardFallbacks());
        assertEquals(forwardedBefore, scriptPlacement.getForwarded());
    }

    @Test
    public void test_call_procedure_falls_back_to_local_when_the_target_is_unreachable() throws Exception {
        routableMembership();
        final var fallbacksBefore = scriptPlacement.getForwardFallbacks();
        assertNull(
                router.forward(new CallProcedureRequest(TestGlobals.DB, "proc", null), "{}", false, "someuser", null));
        assertEquals(fallbacksBefore + 1, scriptPlacement.getForwardFallbacks());
    }

    // A script op is rejected with 409-6 inside an open transaction, so it must never reach placement.
    @Test
    public void test_script_ops_are_not_routed_inside_a_transaction() throws Exception {
        routableMembership();
        final var fallbacksBefore = scriptPlacement.getForwardFallbacks();
        assertNull(router.forward(runScript(), "{}", true, "someuser", java.util.UUID.randomUUID()));
        assertEquals(fallbacksBefore, scriptPlacement.getForwardFallbacks());
    }

    @Test
    public void test_null_when_ownership_not_established() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of()));
        assertNull(router.forward(save(), "{}", false, null, null));
    }
}
