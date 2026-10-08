package org.techhouse.conn;

import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public record FinishedSlice(String txId, OperationType type, OperationResponse response, long finishedAtMillis) {
    public boolean expiredAt(long nowMillis, long retentionMillis) {
        return nowMillis - finishedAtMillis > retentionMillis;
    }
}
