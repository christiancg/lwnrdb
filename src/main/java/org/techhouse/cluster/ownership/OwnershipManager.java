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

    public int majority() {
        final var denominator = Math.max(clusterConfig.expectedSize(), view.size());
        return (denominator / 2) + 1;
    }

    public boolean hasQuorum() {
        return view.aliveCount() >= majority();
    }

    public String ownerAddress(String dbName, String collName) {
        final var owner = ownerFor(dbName, collName);
        if (owner == null) {
            return null;
        }
        final var node = view.find(owner);
        return node != null ? node.address().toString() : null;
    }

    // The admin coordinator is simply the owner of a reserved ring key, so it is chosen and handed off
    // by the same consistent-hash + membership machinery as any collection owner.
    public boolean isAdminCoordinator() {
        return isOwner(Globals.ADMIN_DB_NAME, Globals.CLUSTER_ADMIN_COORDINATOR_KEY);
    }

    public String adminCoordinatorAddress() {
        return ownerAddress(Globals.ADMIN_DB_NAME, Globals.CLUSTER_ADMIN_COORDINATOR_KEY);
    }
}
