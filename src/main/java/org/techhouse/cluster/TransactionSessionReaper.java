package org.techhouse.cluster;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.techhouse.log.Logger;
import org.techhouse.ops.TransactionOperationHelper;

public class TransactionSessionReaper implements MembershipListener {
    private final Logger logger = Logger.logFor(TransactionSessionReaper.class);
    private final ExecutorService worker = Executors
            .newSingleThreadExecutor(Thread.ofVirtual().name("tx-session-reaper-", 0).factory());

    @Override
    public void onMembershipChanged(MembershipView view) {
        worker.execute(() -> {
            try {
                TransactionOperationHelper.reapTransactionsForDeparted(view);
            } catch (Exception e) {
                logger.warning("Failed to reap transactions for departed nodes: " + e.getMessage());
            }
        });
    }
}
