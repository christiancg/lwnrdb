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

    public List<String> aliveNodeIds() {
        return aliveMembers().stream().map(NodeInfo::getNodeId).sorted().toList();
    }

    public int size() {
        return members.size();
    }

    public int aliveCount() {
        return aliveMembers().size();
    }
}
