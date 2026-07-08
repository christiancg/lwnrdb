package org.techhouse.cluster;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class Replicator {
    private final Logger logger = Logger.logFor(Replicator.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);

    public ReplicationOutcome broadcast(ReplicationPayload payload) {
        final var self = membershipService.getSelf();
        final var timeout = clusterConfig.replicationAckTimeoutMs();
        // The owner has already committed locally, so it counts as one towards the majority.
        final var requiredAcks = Math.max(0, ownershipManager.majority() - 1);
        final var latch = new CountDownLatch(requiredAcks);
        for (final var member : membershipService.membershipView().aliveMembers()) {
            if (member.getNodeId().equals(self.getNodeId())) {
                continue;
            }
            final var address = member.address();
            final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE, clusterConfig.secret(), self,
                    null);
            message.setReplication(payload);
            Thread.ofVirtual().name("cluster-replicate").start(() -> sendTo(address, message, latch));
        }
        try {
            return latch.await(timeout, TimeUnit.MILLISECONDS)
                    ? ReplicationOutcome.QUORUM_MET
                    : ReplicationOutcome.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReplicationOutcome.TIMEOUT;
        }
    }

    private void sendTo(NodeAddress address, ClusterMessage message, CountDownLatch latch) {
        try {
            final var ack = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (ack.getType() == ClusterMessageType.REPLICATE_ACK) {
                latch.countDown();
            } else {
                logger.warning("Replication to " + address + " was not acknowledged: " + ack.getErrorMessage());
            }
        } catch (Exception e) {
            logger.warning("Replication to " + address + " failed: " + e.getMessage());
        }
    }
}
