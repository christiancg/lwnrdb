package org.techhouse.ops;

import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class BulkSaveEffects {
    private BulkSaveEffects() {
    }

    public static OperationResponse executeAndPublish(BulkSaveRequest request, String actingUser,
            StagedTriggerRuns staged) throws Exception {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            return ClusterWriteHelper.afterBulkSave(dbName, collName, TriggerHelper.runStaged(staged, dbName, collName,
                    actingUser, request.getTriggerDepth(), () -> SaveOperationHelper.executeBulkSave(request)));
        } catch (PartialBulkSaveException e) {
            ClusterWriteHelper.afterBulkSave(dbName, collName, e.committed());
            throw e;
        }
    }
}
