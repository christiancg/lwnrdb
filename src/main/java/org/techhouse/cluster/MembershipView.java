package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class MembershipView {
    private final List<NodeInfo> members;

    public MembershipView(Collection<NodeInfo> members) {
        this.members = List.copyOf(members);
    }

    public List<NodeInfo> getMembers() {
        return members;
    }

    public NodeInfo find(String nodeId) {
        for (var member : members) {
            if (member.getNodeId().equals(nodeId)) {
                return member;
            }
        }
        return null;
    }

    public List<NodeInfo> aliveMembers() {
        final var alive = new ArrayList<NodeInfo>();
        for (var member : members) {
            if (member.getState() == NodeState.ALIVE) {
                alive.add(member);
            }
        }
        return alive;
    }

    // Null-safe on self: a node's own identity is only set when it joins, so a sweep firing before that
    // would otherwise dereference null.
    public List<NodeInfo> peers(NodeInfo self) {
        final var peers = new ArrayList<NodeInfo>();
        for (final var member : members) {
            if (member.getState() == NodeState.ALIVE
                    && (self == null || !member.getNodeId().equals(self.getNodeId()))) {
                peers.add(member);
            }
        }
        return peers;
    }

    public List<String> aliveNodeIds() {
        final var ids = new ArrayList<String>();
        for (final var member : members) {
            if (member.getState() == NodeState.ALIVE) {
                ids.add(member.getNodeId());
            }
        }
        ids.sort(null);
        return ids;
    }

    public int size() {
        return members.size();
    }

    public int aliveCount() {
        var alive = 0;
        for (final var member : members) {
            if (member.getState() == NodeState.ALIVE) {
                alive++;
            }
        }
        return alive;
    }
}
