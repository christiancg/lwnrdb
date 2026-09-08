package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.TriggerRunRow;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TriggerRunResolution;

public class TriggerRunDirectory {
    private final Logger logger = Logger.logFor(TriggerRunDirectory.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);

    public List<TriggerRunRow> localRuns(TriggerRunStatus filter) {
        return TriggerRunResolution.localRows(filter);
    }

    public List<JsonObject> listClusterWide(TriggerRunStatus filter) {
        final var now = System.currentTimeMillis();
        final var selfAddress = selfAddress();
        final var rows = new ArrayList<JsonObject>();
        for (final var run : localRuns(filter)) {
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
            for (final var run : requestList(member.address(), filter)) {
                rows.add(toJson(run, memberAddress, now));
            }
        }
        return rows;
    }

    public boolean resolveClusterWide(String runId, String decision) {
        if (TriggerRunResolution.resolveLocal(runId, decision)) {
            return true;
        }
        if (!clusterConfig.isEnabled()) {
            return false;
        }
        final var self = membershipService.getSelf();
        for (final var member : membershipService.membershipView().aliveMembers()) {
            if (self != null && member.getNodeId().equals(self.getNodeId())) {
                continue;
            }
            if (requestResolve(member.address(), runId, decision)) {
                return true;
            }
        }
        return false;
    }

    private String selfAddress() {
        return membershipService.getSelf() != null
                ? membershipService.getSelf().address().toString()
                : Globals.STANDALONE_NODE_ID;
    }

    private JsonObject toJson(TriggerRunRow run, String nodeAddress, long now) {
        final var row = new JsonObject();
        row.addProperty("runId", run.getRunId());
        row.addProperty("node", nodeAddress);
        row.addProperty("status", run.getStatus());
        row.addProperty("database", run.getDatabase());
        row.addProperty("collection", run.getCollection());
        row.addProperty("trigger", run.getTriggerName());
        row.addProperty("procedure", run.getProcedureName());
        row.addProperty("event", run.getEventType());
        row.addProperty("attempts", (long) run.getAttempts());
        row.addProperty("lastError", run.getLastError());
        row.addProperty("ageMs", now - run.getFiredAt());
        row.addProperty("nextAttemptAt", run.getNextAttemptAt());
        return row;
    }

    private List<TriggerRunRow> requestList(NodeAddress address, TriggerRunStatus filter) {
        final var message = new ClusterMessage(null, ClusterMessageType.LIST_TRIGGER_RUNS, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setTriggerRunDecision(filter == null ? null : filter.name());
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.LIST_TRIGGER_RUNS_ACK && response.getTriggerRuns() != null) {
                return response.getTriggerRuns();
            }
            return List.of();
        } catch (Exception e) {
            logger.warning("LIST_TRIGGER_RUNS request to " + address + " failed: " + e.getMessage());
            return List.of();
        }
    }

    private boolean requestResolve(NodeAddress address, String runId, String decision) {
        final var message = new ClusterMessage(null, ClusterMessageType.RESOLVE_TRIGGER_RUN, clusterConfig.secret(),
                membershipService.getSelf(), null);
        message.setTriggerRunId(runId);
        message.setTriggerRunDecision(decision);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            return response.getType() == ClusterMessageType.RESOLVE_TRIGGER_RUN_ACK && response.isTriggerRunResolved();
        } catch (Exception e) {
            logger.warning("RESOLVE_TRIGGER_RUN request to " + address + " failed: " + e.getMessage());
            return false;
        }
    }
}
