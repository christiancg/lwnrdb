package org.techhouse.cluster;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
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
        return awaitQuorum(ClusterMessageType.REPLICATE_ACK, () -> {
            final var message = replicateMessage(ClusterMessageType.REPLICATE);
            message.setReplication(payload);
            return message;
        });
    }

    public ReplicationOutcome broadcastTx(TxReplicationPayload payload) {
        return awaitQuorum(ClusterMessageType.REPLICATE_TX_ACK, () -> {
            final var message = replicateMessage(ClusterMessageType.REPLICATE_TX);
            message.setTxReplication(payload);
            return message;
        });
    }

    public ReplicationOutcome broadcastAdmin(AdminSnapshotPayload records) {
        return awaitQuorum(ClusterMessageType.REPLICATE_ADMIN_ACK, () -> {
            final var message = replicateMessage(ClusterMessageType.REPLICATE_ADMIN);
            message.setAdminSnapshot(records);
            return message;
        });
    }

    public ReplicationOutcome broadcastReindex(String rawRequest) {
        return awaitQuorum(ClusterMessageType.REINDEX_BROADCAST_ACK, () -> {
            final var message = replicateMessage(ClusterMessageType.REINDEX_BROADCAST);
            message.setForwardBody(ForwardBody.encode(rawRequest));
            return message;
        });
    }

    private ClusterMessage replicateMessage(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
    }

    private ReplicationOutcome awaitQuorum(ClusterMessageType ackType, Supplier<ClusterMessage> messageFactory) {
        final var self = membershipService.getSelf();
        final var timeout = clusterConfig.replicationAckTimeoutMs();
        // The coordinator has already applied the change locally, so it counts as one towards the majority.
        final var requiredAcks = Math.max(0, ownershipManager.majority() - 1);
        final var latch = new CountDownLatch(requiredAcks);
        for (final var address : distinctPeerAddresses(self)) {
            Thread.ofVirtual().name("cluster-replicate")
                    .start(() -> sendTo(address, messageFactory.get(), ackType, latch));
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

    private Set<NodeAddress> distinctPeerAddresses(NodeInfo self) {
        final var addresses = new LinkedHashSet<NodeAddress>();
        for (final var member : membershipService.membershipView().peers(self)) {
            if (!addresses.add(member.address())) {
                logger.warning("Two node ids advertise " + member.address()
                        + "; counting it once, because both requests ride one socket to one process");
            }
        }
        return addresses;
    }

    private void sendTo(NodeAddress address, ClusterMessage message, ClusterMessageType ackType, CountDownLatch latch) {
        try {
            final var ack = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (ack.getType() == ackType) {
                latch.countDown();
            } else {
                logger.warning("Replication to " + address + " was not acknowledged: " + ack.getErrorMessage());
            }
        } catch (Exception e) {
            logger.warning("Replication to " + address + " failed: " + e.getMessage());
        }
    }
}
