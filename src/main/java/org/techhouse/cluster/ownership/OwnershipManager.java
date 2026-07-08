package org.techhouse.cluster.ownership;

import java.util.List;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.MembershipListener;
import org.techhouse.cluster.MembershipView;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;

public class OwnershipManager implements MembershipListener {
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private volatile String selfNodeId;
    private volatile MembershipView view = new MembershipView(List.of());
    private volatile HashRing ring = new HashRing(List.of(), 1);

    public void setSelfNodeId(String selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        this.view = view;
        this.ring = new HashRing(view.aliveNodeIds(), clusterConfig.virtualNodesPerNode());
    }

    public String ownerFor(String dbName, String collName) {
        return ring.owner(dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
    }

    public boolean isOwner(String dbName, String collName) {
        final var owner = ownerFor(dbName, collName);
        return owner != null && owner.equals(selfNodeId);
    }

    public boolean hasQuorum() {
        final var denominator = Math.max(clusterConfig.expectedSize(), view.size());
        final var majority = (denominator / 2) + 1;
        return view.aliveCount() >= majority;
    }
}
