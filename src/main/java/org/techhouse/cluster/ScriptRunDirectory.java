package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.RunningScript;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScriptRunRegistry;

public class ScriptRunDirectory {
    private final Logger logger = Logger.logFor(ScriptRunDirectory.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);

    public List<RunningScript> localRuns() {
        final var rows = new ArrayList<RunningScript>();
        for (final var run : registry.list()) {
            rows.add(new RunningScript(run.runId(), run.kind().name(), run.database(), run.name(), run.username(),
                    run.startedAt()));
        }
        return rows;
    }

    public List<JsonObject> listClusterWide() {
        final var now = System.currentTimeMillis();
        final var selfAddress = selfAddress();
        final var rows = new ArrayList<JsonObject>();
        for (final var run : localRuns()) {
            rows.add(toJson(run, selfAddress, now));
        }
        if (!clusterConfig.isEnabled()) {
            return rows;
        }
        final var self = membershipService.getSelf();
        for (final var member : membershipService.membershipView().peers(self)) {
            final var memberAddress = member.address().toString();
            for (final var run : requestListScripts(member.address())) {
                rows.add(toJson(run, memberAddress, now));
            }
        }
        return rows;
    }

    public boolean cancelClusterWide(String runId) {
        if (registry.cancel(runId)) {
            return true;
        }
        if (!clusterConfig.isEnabled()) {
            return false;
        }
        final var self = membershipService.getSelf();
        var cancelled = false;
        for (final var member : membershipService.membershipView().peers(self)) {
            cancelled |= requestCancel(member.address(), runId);
        }
        return cancelled;
    }

    private String selfAddress() {
        return membershipService.getSelf() != null
                ? membershipService.getSelf().address().toString()
                : Globals.STANDALONE_NODE_ID;
    }

    private JsonObject toJson(RunningScript run, String nodeAddress, long now) {
        final var row = new JsonObject();
        row.addProperty("runId", run.getRunId());
        row.addProperty("node", nodeAddress);
        row.addProperty("kind", run.getKind());
        row.addProperty("database", run.getDatabase());
        row.addProperty("name", run.getName());
        row.addProperty("username", run.getUsername());
        row.addProperty("ageMs", now - run.getStartedAt());
        return row;
    }

    private List<RunningScript> requestListScripts(NodeAddress address) {
        final var message = new ClusterMessage(null, ClusterMessageType.LIST_SCRIPTS, clusterConfig.secret(),
                membershipService.getSelf(), null);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.LIST_SCRIPTS_ACK && response.getRunningScripts() != null) {
                return response.getRunningScripts();
            }
            return List.of();
        } catch (Exception e) {
            logger.warning("LIST_SCRIPTS request to " + address + " failed: " + e.getMessage());
            return List.of();
        }
    }

    private boolean requestCancel(NodeAddress address, String runId) {
        final var message = new ClusterMessage(null, ClusterMessageType.CANCEL_SCRIPT, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setCancelRunId(runId);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            return response.getType() == ClusterMessageType.CANCEL_SCRIPT_ACK && response.isCancelledRun();
        } catch (Exception e) {
            logger.warning("CANCEL_SCRIPT request to " + address + " failed: " + e.getMessage());
            return false;
        }
    }
}
