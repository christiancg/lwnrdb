package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ioc.IocContainer;

// The envelope every outbound request carries: this node's identity and the shared secret. The
// transport stays with each caller, which holds its own connection pool.
final class PeerRequest {
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final org.techhouse.cluster.membership.MembershipService membershipService = IocContainer
            .get(org.techhouse.cluster.membership.MembershipService.class);

    private PeerRequest() {
    }

    static ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
    }
}
