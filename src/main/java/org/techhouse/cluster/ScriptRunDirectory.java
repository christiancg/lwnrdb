package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.RunningScript;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.ScriptRunRegistry;

/**
 * Cluster-wide visibility and cancellation of running scripts, for the admin LIST_SCRIPTS and CANCEL_SCRIPT
 * operations. Both fan out because a script has no owner to route to: {@code ScriptPlacement} sends a run to
 * whichever live node is least loaded, so the run an operator is looking for is usually not on the node they
 * connected to. The node handling the request collects its own runs and queries every other live member, the
 * same pattern {@link Tx2pcDirectory} uses for LIST_TRANSACTIONS.
 */
public class ScriptRunDirectory {
    private final Logger logger = Logger.logFor(ScriptRunDirectory.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);

    // This node's own runs, as reported over LIST_SCRIPTS.
    public List<RunningScript> localRuns() {
        final var rows = new ArrayList<RunningScript>();
        for (final var run : registry.list()) {
            rows.add(new RunningScript(run.runId(), run.kind().name(), run.database(), run.name(), run.username(),
                    run.startedAt()));
        }
        return rows;
    }

    // Every run executing on this node and on each live peer, one JSON row per run.
    public List<JsonObject> listClusterWide() {
        // One `now` for the whole listing, so two rows that started together report the same age.
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
        for (final var member : membershipService.membershipView().aliveMembers()) {
            if (self != null && member.getNodeId().equals(self.getNodeId())) {
                continue;
            }
            final var memberAddress = member.address().toString();
            for (final var run : requestListScripts(member.address())) {
                rows.add(toJson(run, memberAddress, now));
            }
        }
        return rows;
    }

    /**
     * Cancels the run wherever it is executing.
     *
     * @return {@code true} when some node was running it and has asked it to stop; {@code false} when no live
     *         node has it, which is also the answer for a run that has already finished.
     */
    public boolean cancelClusterWide(String runId) {
        if (registry.cancel(runId)) {
            return true;
        }
        if (!clusterConfig.isEnabled()) {
            return false;
        }
        final var self = membershipService.getSelf();
        var cancelled = false;
        for (final var member : membershipService.membershipView().aliveMembers()) {
            if (self != null && member.getNodeId().equals(self.getNodeId())) {
                continue;
            }
            cancelled |= requestCancel(member.address(), runId);
        }
        return cancelled;
    }

    private String selfAddress() {
        return membershipService.getSelf() != null ? membershipService.getSelf().address().toString() : "local";
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
            // An unreachable peer costs the operator its rows, not the whole listing.
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
