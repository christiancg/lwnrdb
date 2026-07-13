package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.InDoubtTx;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.Tx2pcLog;

/**
 * Cluster-wide discovery of in-doubt (PREPARED) distributed transactions for the admin LIST_TRANSACTIONS
 * operation. The node handling the request collects its own prepared markers and fans out a LIST_TX request
 * to every other live member, then aggregates the responses by distributed-transaction id so an operator can
 * see which transactions are stuck and, if needed, force a resolution with RESOLVE_TRANSACTION.
 */
public class Tx2pcDirectory {
    private final Logger logger = Logger.logFor(Tx2pcDirectory.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);

    // This node's own in-doubt transactions (its PREPARED markers), as reported over LIST_TX.
    public List<InDoubtTx> localInDoubt() throws Exception {
        final var result = new ArrayList<InDoubtTx>();
        for (final var dtxId : Tx2pcLog.preparedDtxIds()) {
            final var marker = Tx2pcLog.readParticipantMarker(dtxId);
            if (marker == null) {
                continue;
            }
            result.add(new InDoubtTx(dtxId, marker.coordinatorAddress(), marker.participants(), marker.preparedAt(),
                    Tx2pcLog.Status.PREPARED.name()));
        }
        return result;
    }

    // Aggregated in-doubt transactions across this node and every live peer, one JSON row per transaction.
    public List<JsonObject> listInDoubtClusterWide() throws Exception {
        final var now = System.currentTimeMillis();
        final var byDtx = new LinkedHashMap<String, Aggregate>();
        final var selfAddress = membershipService.getSelf() != null
                ? membershipService.getSelf().address().toString()
                : "local";
        for (final var tx : localInDoubt()) {
            merge(byDtx, selfAddress, tx);
        }
        final var aliveAddresses = new HashSet<String>();
        aliveAddresses.add(selfAddress);
        if (clusterConfig.isEnabled()) {
            final var self = membershipService.getSelf();
            for (final var member : membershipService.membershipView().aliveMembers()) {
                final var memberAddress = member.address().toString();
                aliveAddresses.add(memberAddress);
                if (self != null && member.getNodeId().equals(self.getNodeId())) {
                    continue;
                }
                for (final var tx : requestListTx(member.address())) {
                    merge(byDtx, memberAddress, tx);
                }
            }
        }
        final var rows = new ArrayList<JsonObject>();
        for (final var aggregate : byDtx.values()) {
            rows.add(aggregate.toJson(now, aliveAddresses));
        }
        return rows;
    }

    private void merge(LinkedHashMap<String, Aggregate> byDtx, String nodeAddress, InDoubtTx tx) {
        final var aggregate = byDtx.computeIfAbsent(tx.getDtxId(), Aggregate::new);
        aggregate.observe(nodeAddress, tx);
    }

    private List<InDoubtTx> requestListTx(NodeAddress address) {
        final var message = new ClusterMessage(null, ClusterMessageType.LIST_TX, clusterConfig.secret(),
                membershipService.getSelf(), null);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.LIST_TX_ACK && response.getInDoubtTransactions() != null) {
                return response.getInDoubtTransactions();
            }
            return List.of();
        } catch (Exception e) {
            logger.warning("LIST_TX request to " + address + " failed: " + e.getMessage());
            return List.of();
        }
    }

    private static final class Aggregate {
        private final String dtxId;
        private String coordinator;
        private final List<String> participants = new ArrayList<>();
        private long earliestPreparedAt = Long.MAX_VALUE;
        private final LinkedHashMap<String, String> perNodeStatus = new LinkedHashMap<>();

        private Aggregate(String dtxId) {
            this.dtxId = dtxId;
        }

        private void observe(String nodeAddress, InDoubtTx tx) {
            if (coordinator == null && tx.getCoordinator() != null) {
                coordinator = tx.getCoordinator();
            }
            for (final var participant : tx.getParticipants()) {
                if (!participants.contains(participant)) {
                    participants.add(participant);
                }
            }
            if (tx.getPreparedAt() > 0 && tx.getPreparedAt() < earliestPreparedAt) {
                earliestPreparedAt = tx.getPreparedAt();
            }
            perNodeStatus.put(nodeAddress, tx.getStatus());
        }

        private JsonObject toJson(long now, HashSet<String> aliveAddresses) {
            final var row = new JsonObject();
            row.addProperty("dtxId", dtxId);
            row.addProperty("coordinator", coordinator);
            row.addProperty("coordinatorReachable", coordinator != null && aliveAddresses.contains(coordinator));
            final var participantsArray = new JsonArray();
            for (final var participant : participants) {
                participantsArray.add(participant);
            }
            row.add("participants", participantsArray);
            row.addProperty("ageMs", earliestPreparedAt == Long.MAX_VALUE ? 0L : now - earliestPreparedAt);
            final var statuses = new JsonObject();
            perNodeStatus.forEach(statuses::addProperty);
            row.add("perNodeStatus", statuses);
            return row;
        }
    }
}
