package org.techhouse.ops;

import java.lang.management.ManagementFactory;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.ServerInfoRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ServerInfoResponse;

public final class ServerInfoHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final Configuration configuration = Configuration.getInstance();

    public enum ServerState {
        READY, SYNCING, SHUTTING_DOWN
    }

    private ServerInfoHelper() {
    }

    public static OperationResponse process(ServerInfoRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.SERVER_INFO, ErrorCode.ERROR_GATHERING_SERVER_INFO,
                () -> new ServerInfoResponse("Ok", build(request.isShuttingDown(), userOf(actingUser))));
    }

    private static AdminUserEntry userOf(String actingUser) {
        return actingUser == null ? null : cache.getAdminUserEntry(actingUser);
    }

    private static JsonObject build(boolean shuttingDown, AdminUserEntry user) {
        final var server = new JsonObject();
        server.addProperty("state", stateOf(shuttingDown).name());
        if (user == null) {
            return server;
        }
        addBuild(server);
        if (user.isAdmin()) {
            server.add("node", buildNode());
        }
        return server;
    }

    private static ServerState stateOf(boolean shuttingDown) {
        if (shuttingDown) {
            return ServerState.SHUTTING_DOWN;
        }
        if (clusterConfig.isEnabled() && !adminAntiEntropyService.hasCompletedAdminSync()) {
            return ServerState.SYNCING;
        }
        return ServerState.READY;
    }

    private static void addBuild(JsonObject server) {
        final var runtime = ManagementFactory.getRuntimeMXBean();
        server.addProperty("version", ServerVersion.current());
        server.addProperty("protocolVersion", Globals.PROTOCOL_VERSION);
        server.addProperty("storageFormatVersion", Globals.STORAGE_FORMAT_VERSION);
        server.addProperty("startedAtMs", runtime.getStartTime());
        server.addProperty("uptimeMs", runtime.getUptime());
    }

    private static JsonObject buildNode() {
        final var node = new JsonObject();
        final var clustered = clusterConfig.isEnabled();
        node.addProperty("clusterEnabled", clustered);
        if (clustered) {
            addMembership(node);
        }
        node.addProperty("syncWrites", configuration.isSyncWrites());
        node.addProperty("tlsEnabled", configuration.isTlsEnabled());
        return node;
    }

    private static void addMembership(JsonObject node) {
        final var self = membershipService.getSelf();
        if (self != null) {
            node.addProperty("nodeId", self.getNodeId());
        }
        node.addProperty("aliveMembers", membershipService.membershipView().aliveMembers().size());
        node.addProperty("expectedSize", clusterConfig.expectedSize());
    }
}
