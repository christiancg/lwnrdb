package org.techhouse.ops;

import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class BulkSaveEffects {
    private BulkSaveEffects() {
    }

    public static OperationResponse executeAndPublish(BulkSaveRequest request, String actingUser) throws Exception {
        try {
            return afterCommitted(request, SaveOperationHelper.executeBulkSave(request), actingUser);
        } catch (PartialBulkSaveException e) {
            afterCommitted(request, e.committed(), actingUser);
            throw e;
        }
    }

    private static OperationResponse afterCommitted(BulkSaveRequest request, OperationResponse committed,
            String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        TriggerHelper.afterBulkSave(dbName, collName, committed, actingUser, request.getTriggerDepth());
        return ClusterWriteHelper.afterBulkSave(dbName, collName, committed);
    }
}
