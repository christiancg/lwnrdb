package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
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
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.CancelScriptRequest;
import org.techhouse.ops.req.ListScriptsRequest;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorScriptControlTest {
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private boolean origRouting;

    private static AdminUserEntry admin() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    // Owning a database is the widest non-admin authority there is, and it still is not enough.
    private static AdminUserEntry databaseOwner() {
        final var permissions = new HashMap<String, PermissionLevel>();
        permissions.put(TestGlobals.DB, PermissionLevel.READ_WRITE);
        return new AdminUserEntry("owner", "hash", false, new HashSet<>(), permissions, new HashMap<>());
    }

    private static CancelScriptRequest cancel(String runId) {
        final var request = new CancelScriptRequest();
        request.setRunId(runId);
        return request;
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

    @Test
    public void test_list_scripts_requires_admin() {
        final var request = new ListScriptsRequest();
        assertTrue(AuthorizationChecker.check(request, admin()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, databaseOwner()).isAllowed());
    }

    @Test
    public void test_cancel_script_requires_admin() {
        final var request = cancel(UUID.randomUUID().toString());
        assertTrue(AuthorizationChecker.check(request, admin()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, databaseOwner()).isAllowed());
    }

    @Test
    public void test_list_scripts_carries_no_payload_to_validate() {
        assertTrue(RequestValidator.validate(new ListScriptsRequest()).isValid());
    }

    @Test
    public void test_cancel_script_rejects_blank_and_non_uuid_run_id() {
        assertFalse(RequestValidator.validate(cancel(null)).isValid());
        assertFalse(RequestValidator.validate(cancel("   ")).isValid());
        assertFalse(RequestValidator.validate(cancel("not-a-uuid")).isValid());
        assertTrue(RequestValidator.validate(cancel(UUID.randomUUID().toString())).isValid());
    }

    // Both operations run on the node that accepted them and fan out themselves, so the router must
    // never claim them - the treatment LIST_TRANSACTIONS gets.
    @Test
    public void test_list_scripts_is_not_routed() throws Exception {
        routableMembership();
        assertNull(router.forward(new ListScriptsRequest(), "{}", false, "admin", null));
        assertNull(router.forward(cancel(UUID.randomUUID().toString()), "{}", false, "admin", null));
    }

    // This node owns nothing, so anything routable would be forwarded away from here.
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
        ownership.setSelfNodeId("other");
        ownership.onMembershipChanged(new MembershipView(List.of(self, other)));
    }

    @Test
    public void test_error_code_is_408_2() {
        assertEquals("408-2", org.techhouse.ops.ErrorCode.SCRIPT_CANCELLED.getCode());
        assertEquals(org.techhouse.ops.OperationStatus.ERROR, org.techhouse.ops.ErrorCode.SCRIPT_CANCELLED.getStatus());
    }
}
