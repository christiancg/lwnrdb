package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.resp.DeleteManyResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.exceptions.ScriptCallableException;

final class ConditionalDelete {
    private static final Configuration configuration = Configuration.getInstance();

    private final DeleteRequest request;
    private final List<String> ids;
    private final List<DbEntry> captured;
    private final OperationResponse refusal;
    private ClusterWriteHelper.ReservedDelete reserved;

    private ConditionalDelete(DeleteRequest request, List<String> ids, List<DbEntry> captured,
            OperationResponse refusal) {
        this.request = request;
        this.ids = ids;
        this.captured = captured;
        this.refusal = refusal;
    }

    private static ConditionalDelete refused(DeleteRequest request, OperationResponse refusal) {
        return new ConditionalDelete(request, List.of(), List.of(), refusal);
    }

    static ConditionalDelete prepare(DeleteRequest request, String actingUser) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var ownershipError = ClusterWriteHelper.refusalUnderLock(OperationType.DELETE, dbName, collName);
        if (ownershipError != null) {
            return refused(request, ownershipError);
        }
        final var hooked = BeforeHookContext.hasHooksFor(dbName, collName, EventType.DELETED);
        final var triggered = TriggerHelper.firesOnDelete(dbName, collName, request.getTriggerDepth());
        final var ids = new ArrayList<String>();
        final var documents = new ArrayList<DbEntry>();
        try (var context = PipelineContext.fromConfiguration(null)) {
            final var held = new HeldRows(context);
            final var limit = configuration.intValue(ConfigKey.DELETE_MAX_MATCHES);
            try (var matches = context.watch(
                    FilterOperatorHelper.processOperator(request.getCondition(), null, dbName, collName, context))) {
                final var iterator = matches.iterator();
                while (iterator.hasNext()) {
                    final var document = iterator.next();
                    if (ids.size() == limit) {
                        return refused(request,
                                new OperationResponse(OperationType.DELETE, ErrorCode.DELETE_MATCHES_TOO_MANY));
                    }
                    ids.add(document.get(Globals.PK_FIELD).asJsonString().getValue());
                    if (hooked || triggered) {
                        held.hold(document);
                        documents.add(DbEntry.fromJsonObject(dbName, collName, document));
                    }
                }
            }
        } catch (ScriptCallableException scriptFailure) {
            return refused(request, new OperationResponse(OperationType.DELETE, scriptFailure.getMessage(),
                    ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName())));
        } catch (AggregateLimitException limit) {
            return refused(request, new OperationResponse(OperationType.DELETE, limit.getErrorCode()));
        }
        final var hookError = hooked ? BeforeHookHelper.beforeDeleteAll(dbName, collName, documents, actingUser) : null;
        if (hookError != null) {
            return refused(request, hookError);
        }
        return new ConditionalDelete(request, ids, triggered ? documents : List.of(), null);
    }

    OperationResponse refusal() {
        return refusal;
    }

    List<DbEntry> captured() {
        return captured;
    }

    OperationResponse execute() throws Exception {
        if (ids.isEmpty()) {
            return new DeleteManyResponse(0);
        }
        reserved = ClusterWriteHelper.deleteReserved(request.getDatabaseName(), request.getCollectionName(), ids);
        if (reserved == null) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.NOT_COLLECTION_OWNER);
        }
        return new DeleteManyResponse(reserved.removed().size());
    }

    OperationResponse replicated(OperationResponse response) {
        return ClusterWriteHelper.afterDeleteMany(request.getDatabaseName(), request.getCollectionName(), reserved,
                response);
    }
}
