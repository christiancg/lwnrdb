package org.techhouse.test;

import java.util.List;
import org.mockito.invocation.InvocationOnMock;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TxCommitLog;

public final class TransactionRecordCalls {
    public static final String WRITE = "writeTransactionRecords";

    private TransactionRecordCalls() {
    }

    public static boolean isWrite(InvocationOnMock invocation) {
        return WRITE.equals(invocation.getMethod().getName());
    }

    public static List<AdminTransactionEntry> insertsOf(InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    public static List<String> deletesOf(InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    public static boolean inserts(InvocationOnMock invocation, String recordId) {
        return isWrite(invocation) && insertsOf(invocation).stream().anyMatch(entry -> entry.get_id().equals(recordId));
    }

    public static boolean deletes(InvocationOnMock invocation, String recordId) {
        return isWrite(invocation) && deletesOf(invocation).contains(recordId);
    }

    public static boolean clearsLocalCommit(InvocationOnMock invocation, String txId) {
        return deletes(invocation, TxCommitLog.markerId(txId));
    }

    public static boolean writesLocalCommit(InvocationOnMock invocation, String txId) {
        return inserts(invocation, TxCommitLog.markerId(txId));
    }

    public static void persistBufferedOps(Transaction transaction) throws Exception {
        if (!transaction.areOpsDurable()) {
            AdminOperationHelper.writeTransactionRecords(transaction.getPendingOps(), List.of());
            transaction.markOpsDurable();
        }
    }
}
