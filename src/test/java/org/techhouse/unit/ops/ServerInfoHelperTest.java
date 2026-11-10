package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ServerInfoHelper;
import org.techhouse.ops.req.ServerInfoRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ServerInfoResponse;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ServerInfoHelperTest {
    private static final String ADMIN = "server_info_admin";
    private static final String READER = "server_info_reader";
    private static final String NODE_ID = "node-under-test";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.ensureAdminUser(ADMIN);
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(READER, "unused", false, Set.of(), Map.of(), Map.of()));
    }

    @AfterAll
    static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @AfterEach
    void restoreClusterState() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(adminAntiEntropyService, "started", false);
        TestUtils.setPrivateField(membershipService, "self", null);
        members().remove(NODE_ID);
    }

    private Map<String, NodeInfo> members() throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(membershipService, "members", new ReflectionUtils.TypeToken<>() {
        });
    }

    private static Set<String> keysOf(JsonObject object) {
        return object.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private JsonObject serverAs(String username) {
        return serverAs(username, new ServerInfoRequest());
    }

    private JsonObject serverAs(String username, ServerInfoRequest request) {
        final var clientId = clientTracker.registerForwardedClient(username);
        try {
            final var response = processor.processMessage(request, clientId);
            assertEquals(OperationStatus.OK, response.getStatus());
            assertEquals(OperationType.SERVER_INFO, response.getType());
            return ((ServerInfoResponse) response).getServer();
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void test_anonymous_caller_sees_only_the_state() {
        final var response = (ServerInfoResponse) processor.processMessage(new ServerInfoRequest());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(Set.of("state"), keysOf(response.getServer()));
        assertEquals("READY", response.getServer().get("state").asJsonString().getValue());
    }

    @Test
    public void test_a_deleted_user_sees_only_the_state() {
        assertEquals(Set.of("state"), keysOf(serverAs("server_info_nobody")));
    }

    @Test
    public void test_an_authenticated_user_sees_the_build_but_no_node() {
        final var server = serverAs(READER);

        assertEquals(Set.of("state", "version", "protocolVersion", "startedAtMs", "uptimeMs"), keysOf(server));
        assertEquals(ServerVersion.UNPACKAGED, server.get("version").asJsonString().getValue());
        assertEquals(Globals.PROTOCOL_VERSION, server.get("protocolVersion").asJsonNumber().getValue().intValue());
        assertTrue(server.get("startedAtMs").asJsonNumber().getValue().longValue() > 0);
        assertTrue(server.get("uptimeMs").asJsonNumber().getValue().longValue() >= 0);
    }

    @Test
    public void test_an_admin_sees_a_standalone_node() {
        final var node = serverAs(ADMIN).get("node").asJsonObject();

        assertEquals(Set.of("clusterEnabled", "syncWrites", "tlsEnabled"), keysOf(node));
        assertFalse(node.get("clusterEnabled").asJsonBoolean().getValue());
        assertEquals(configuration.isSyncWrites(), node.get("syncWrites").asJsonBoolean().getValue());
        assertEquals(configuration.isTlsEnabled(), node.get("tlsEnabled").asJsonBoolean().getValue());
    }

    @Test
    public void test_an_admin_sees_the_cluster_membership() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        final var self = new NodeInfo(NODE_ID, "127.0.0.1", 9990, NodeState.ALIVE, 1, 1);
        TestUtils.setPrivateField(membershipService, "self", self);
        members().put(NODE_ID, self);

        final var node = serverAs(ADMIN).get("node").asJsonObject();

        assertTrue(node.get("clusterEnabled").asJsonBoolean().getValue());
        assertEquals(NODE_ID, node.get("nodeId").asJsonString().getValue());
        assertEquals(1, node.get("aliveMembers").asJsonNumber().getValue().intValue());
        assertEquals(configuration.intValue(ConfigKey.CLUSTER_EXPECTED_SIZE),
                node.get("expectedSize").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_a_cluster_node_without_a_self_omits_the_node_id() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);

        final var node = serverAs(ADMIN).get("node").asJsonObject();

        assertFalse(node.has("nodeId"));
        assertEquals(0, node.get("aliveMembers").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_state_is_shutting_down_when_marked() {
        final var request = new ServerInfoRequest();
        request.markShuttingDown(true);

        assertEquals("SHUTTING_DOWN", serverAs(READER, request).get("state").asJsonString().getValue());
    }

    @Test
    public void test_state_is_syncing_until_the_admin_sync_completes() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        TestUtils.setPrivateField(adminAntiEntropyService, "started", true);
        final var completed = TestUtils.getPrivateField(adminAntiEntropyService, "adminSyncCompleted",
                AtomicBoolean.class);
        final var original = completed.get();
        try {
            completed.set(false);
            assertEquals("SYNCING", serverAs(READER).get("state").asJsonString().getValue());
            completed.set(true);
            assertEquals("READY", serverAs(READER).get("state").asJsonString().getValue());
        } finally {
            completed.set(original);
        }
    }

    @Test
    public void test_a_standalone_node_is_ready_even_when_the_sync_never_ran() throws Exception {
        TestUtils.setPrivateField(adminAntiEntropyService, "started", true);
        final var completed = TestUtils.getPrivateField(adminAntiEntropyService, "adminSyncCompleted",
                AtomicBoolean.class);

        assertFalse(completed.get());
        assertEquals("READY", serverAs(READER).get("state").asJsonString().getValue());
    }

    @Test
    public void test_a_failure_answers_error_gathering_server_info() {
        final var request = mock(ServerInfoRequest.class);
        when(request.isShuttingDown()).thenThrow(new IllegalStateException("boom"));

        final OperationResponse response = ServerInfoHelper.process(request, READER);

        assertEquals(ErrorCode.ERROR_GATHERING_SERVER_INFO.getCode(), response.getErrorCode());
    }
}
