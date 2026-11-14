package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

public final class ConditionalDeleteBuffer {
    private static final Configuration configuration = Configuration.getInstance();

    private record PreparedDelete(String id, JsonObject payload, long bytes) {
    }

    private ConditionalDeleteBuffer() {
    }

    public static OperationResponse buffer(DeleteRequest request, Transaction transaction, Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.DELETE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var lockResult = TransactionBuffer.lockedRefusal(transaction, OperationType.DELETE, dbName, collName,
                    1, onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final List<JsonObject> matches;
            try {
                matches = matchesOf(request, transaction);
            } catch (ScriptCallableException scriptFailure) {
                return new OperationResponse(OperationType.DELETE, scriptFailure.getMessage(),
                        ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
            } catch (AggregateLimitException limit) {
                return new OperationResponse(OperationType.DELETE, limit.getErrorCode());
            }
            if (matches.size() > configuration.intValue(ConfigKey.DELETE_MAX_MATCHES)) {
                return new OperationResponse(OperationType.DELETE, ErrorCode.DELETE_MATCHES_TOO_MANY);
            }
            final var collId = Cache.getCollectionIdentifier(dbName, collName);
            final var prepared = new ArrayList<PreparedDelete>(matches.size());
            var totalBytes = 0L;
            for (final var match : matches) {
                final var id = match.get(Globals.PK_FIELD).asJsonString().getValue();
                final var hookError = TransactionWrites.runDeleteBeforeHooks(transaction, collId, dbName, collName, id);
                if (hookError != null) {
                    return hookError;
                }
                final var payload = TransactionBuffer.deletePayload(transaction, collId, dbName, collName, id);
                final var bytes = TransactionBuffer.byteSizeOf(dbName, collName, payload);
                prepared.add(new PreparedDelete(id, payload, bytes));
                totalBytes += bytes;
            }
            if (prepared.isEmpty()) {
                return new DeleteManyResponse(0);
            }
            final var sizeRefusal = TransactionBuffer.sizeRefusal(transaction, OperationType.DELETE, prepared.size(),
                    totalBytes);
            if (sizeRefusal != null) {
                return sizeRefusal;
            }
            for (final var delete : prepared) {
                TransactionBuffer.bufferPreparedDelete(transaction, dbName, collName, collId, delete.id(),
                        delete.payload(), delete.bytes());
            }
            return new DeleteManyResponse(prepared.size());
        });
    }

    private static List<JsonObject> matchesOf(DeleteRequest request, Transaction transaction) throws IOException {
        final var filter = new AggregateRequest(request.getDatabaseName(), request.getCollectionName());
        filter.setAggregationSteps(List.of(new FilterAggregationStep(request.getCondition())));
        final var overlay = transaction
                .overlayFor(Cache.getCollectionIdentifier(request.getDatabaseName(), request.getCollectionName()));
        try (var context = PipelineContext.fromConfiguration(null)) {
            return overlay == null || overlay.isEmpty()
                    ? AggregationOperationHelper.processAggregation(filter, null, transaction, context)
                    : OverlayRead.runPipeline(filter, transaction, context);
        }
    }
}
