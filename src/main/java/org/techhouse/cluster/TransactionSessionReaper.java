package org.techhouse.cluster;

import org.techhouse.ops.TransactionOperationHelper;

/**
 * Rolls back forwarded transactions stranded on this (owner) node when their originating edge node leaves the
 * cluster. Registered as a membership listener so an edge-node crash cannot hold the owner's write locks
 * indefinitely — the interim safety net until Phase 5b adds a full transaction recovery protocol.
 */
public class TransactionSessionReaper implements MembershipListener {
    @Override
    public void onMembershipChanged(MembershipView view) {
        TransactionOperationHelper.reapTransactionsForDeparted(view);
    }
}
