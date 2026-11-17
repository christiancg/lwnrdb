package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.SavePatch;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveWhereResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

public final class ConditionalSaveBuffer {
    private static final Configuration configuration = Configuration.getInstance();

    private ConditionalSaveBuffer() {
    }

    public static OperationResponse buffer(SaveRequest request, Transaction transaction, Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.SAVE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var lockResult = TransactionBuffer.lockedRefusal(transaction, OperationType.SAVE, dbName, collName, 1,
                    onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final List<JsonObject> matches;
            try {
                matches = ConditionMatches.of(dbName, collName, request.getCondition(), transaction);
            } catch (ScriptCallableException scriptFailure) {
                return new OperationResponse(OperationType.SAVE, scriptFailure.getMessage(),
                        ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
            } catch (AggregateLimitException limit) {
                return new OperationResponse(OperationType.SAVE, limit.getErrorCode());
            }
            if (matches.size() > configuration.intValue(ConfigKey.UPDATE_MAX_MATCHES)) {
                return new OperationResponse(OperationType.SAVE, ErrorCode.UPDATE_MATCHES_TOO_MANY);
            }
            if (matches.isEmpty()) {
                return new SaveWhereResponse(0);
            }
            final var merged = new ArrayList<JsonObject>(matches.size());
            for (final var match : matches) {
                merged.add(SavePatch.merged(match, request.getObject()));
            }
            return SaveWhereResponse.answering(TransactionBuffer.bufferBulkSave(SavePatch.bulkSaveOf(request, merged),
                    transaction, onLockTimeout));
        });
    }
}
