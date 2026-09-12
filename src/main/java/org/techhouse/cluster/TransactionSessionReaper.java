package org.techhouse.cluster;

import org.techhouse.ops.TransactionOperationHelper;

public class TransactionSessionReaper implements MembershipListener {
    @Override
    public void onMembershipChanged(MembershipView view) {
        TransactionOperationHelper.reapTransactionsForDeparted(view);
    }
}
