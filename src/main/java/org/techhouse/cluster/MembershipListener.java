package org.techhouse.cluster;

@FunctionalInterface
public interface MembershipListener {
    void onMembershipChanged(MembershipView view);
}
