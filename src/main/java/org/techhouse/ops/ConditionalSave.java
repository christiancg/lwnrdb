package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveWhereResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

final class ConditionalSave {
    private static final Configuration configuration = Configuration.getInstance();

    private final BulkSaveRequest merged;
    private final OperationResponse answered;

    private ConditionalSave(BulkSaveRequest merged, OperationResponse answered) {
        this.merged = merged;
        this.answered = answered;
    }

    private static ConditionalSave answered(OperationResponse response) {
        return new ConditionalSave(null, SaveWhereResponse.answering(response));
    }

    static ConditionalSave prepare(SaveRequest request, String actingUser) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var ownershipError = ClusterWriteHelper.refusalUnderLock(OperationType.SAVE, dbName, collName);
        if (ownershipError != null) {
            return answered(ownershipError);
        }
        final List<JsonObject> documents;
        try {
            documents = mergedMatches(request);
        } catch (ScriptCallableException scriptFailure) {
            return answered(new OperationResponse(OperationType.SAVE, scriptFailure.getMessage(),
                    ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName())));
        } catch (AggregateLimitException limit) {
            return answered(new OperationResponse(OperationType.SAVE, limit.getErrorCode()));
        }
        if (documents.isEmpty()) {
            return answered(new SaveWhereResponse(0));
        }
        final var bulk = SavePatch.bulkSaveOf(request, documents);
        final var schemaError = SchemaValidationHelper.check(bulk);
        if (schemaError != null) {
            return answered(schemaError);
        }
        final var hookError = BeforeHookHelper.beforeBulkSave(bulk, actingUser);
        if (hookError != null) {
            return answered(hookError);
        }
        return new ConditionalSave(bulk, null);
    }

    private static List<JsonObject> mergedMatches(SaveRequest request) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var documents = new ArrayList<JsonObject>();
        try (var context = PipelineContext.fromConfiguration(null)) {
            final var held = new HeldRows(context);
            final var limit = configuration.intValue(ConfigKey.UPDATE_MAX_MATCHES);
            try (var matches = context.watch(
                    FilterOperatorHelper.processOperator(request.getCondition(), null, dbName, collName, context))) {
                final var iterator = matches.iterator();
                while (iterator.hasNext()) {
                    final var document = iterator.next();
                    if (documents.size() == limit) {
                        throw new AggregateLimitException(ErrorCode.UPDATE_MATCHES_TOO_MANY);
                    }
                    held.hold(document);
                    documents.add(SavePatch.merged(document, request.getObject()));
                }
            }
        }
        return documents;
    }

    OperationResponse answered() {
        return answered;
    }

    BulkSaveRequest merged() {
        return merged;
    }
}
