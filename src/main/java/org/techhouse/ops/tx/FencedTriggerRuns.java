package org.techhouse.ops.tx;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;

public final class FencedTriggerRuns {
    private FencedTriggerRuns() {
    }

    public static Set<String> consumedByFencedSlices() throws Exception {
        final var runIds = new HashSet<String>();
        for (final var txId : Tx2pcLog.preparedDtxIds()) {
            runIds.addAll(consumedBySlice(txId));
        }
        for (final var txId : TxCommitLog.localCommitTxIds()) {
            runIds.addAll(consumedBySlice(txId));
        }
        return runIds;
    }

    public static Set<String> consumedBySlice(String txId) throws Exception {
        return runIdsIn(AdminOperationHelper.readTransactionOps(Tx2pcLog.sliceOpIds(txId)));
    }

    static Set<String> runIdsIn(List<AdminTransactionEntry> ops) {
        final var runIds = new HashSet<String>();
        for (final var op : ops) {
            final var runId = op.consumedTriggerRunId();
            if (runId != null) {
                runIds.add(runId);
            }
        }
        return runIds;
    }
}
