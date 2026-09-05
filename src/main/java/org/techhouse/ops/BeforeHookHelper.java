package org.techhouse.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

/**
 * Runs the before triggers a write fires, the counterpart of {@link TriggerHelper}'s after-write queueing.
 * Each method returns null to let the write proceed - having rewritten the request's document if a hook
 * replaced it - or the response that refuses it.
 *
 * <p>
 * Called from OperationProcessor's write handlers only, never from SaveOperationHelper or
 * DeleteOperationHelper, for the reason TriggerHelper documents: {@code ReplicatedApplyHelper} and
 * {@code ReplicatedTxApplyHelper} reach those helpers directly, and a hook there would transform a document
 * the owner has already transformed. Unlike TriggerHelper this runs user code, so the caller must already
 * hold the collection write lock - which is what makes a rejection stop the write rather than undo it.
 */
public final class BeforeHookHelper {
    private static final Cache cache = IocContainer.get(Cache.class);

    private BeforeHookHelper() {
    }

    public static OperationResponse beforeSave(SaveRequest request, EventType event, String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (!BeforeHookContext.hasHooksFor(dbName, collName, event)) {
            return null;
        }
        try (var hooks = BeforeHookContext.open(dbName, collName, event, actingUser)) {
            final var outcome = hooks.apply(request.getObject(), request.get_id(), OperationType.SAVE);
            if (outcome.isRejected()) {
                return outcome.rejection();
            }
            request.setObject(outcome.document());
            return null;
        }
    }

    // A bulk save cannot classify each document as an insert or an update before it is written, so a
    // document is offered to whichever hooks watch the event its presence in the PK index implies.
    public static OperationResponse beforeBulkSave(BulkSaveRequest request, String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var created = BeforeHookContext.hasHooksFor(dbName, collName, EventType.CREATED);
        final var updated = BeforeHookContext.hasHooksFor(dbName, collName, EventType.UPDATED);
        if (!created && !updated) {
            return null;
        }
        try (var creates = BeforeHookContext.open(dbName, collName, EventType.CREATED, actingUser);
                var updates = BeforeHookContext.open(dbName, collName, EventType.UPDATED, actingUser)) {
            final var existingIds = new HashSet<String>();
            cache.getPkIndexAndLoadIfNecessary(dbName, collName).forEach(entry -> existingIds.add(entry.getValue()));
            final var objects = new ArrayList<>(request.getObjects());
            for (var i = 0; i < objects.size(); i++) {
                final var object = objects.get(i);
                final var id = idOf(object);
                final var isInsert = id == null || !existingIds.contains(id);
                final var outcome = (isInsert ? creates : updates).apply(object, id, OperationType.BULK_SAVE);
                if (outcome.isRejected()) {
                    return outcome.rejection();
                }
                objects.set(i, outcome.document());
            }
            request.setObjects(objects);
            return null;
        } catch (Exception e) {
            return new OperationResponse(OperationType.BULK_SAVE, ErrorCode.ERROR_BULK_SAVING);
        }
    }

    public static OperationResponse beforeDelete(DeleteRequest request, String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (!BeforeHookContext.hasHooksFor(dbName, collName, EventType.DELETED)) {
            return null;
        }
        final JsonObject document;
        try {
            final var entries = cache.getEntriesByIds(dbName, collName, Set.of(request.get_id()));
            if (entries.isEmpty()) {
                return null;
            }
            document = entries.getFirst().getData();
        } catch (Exception e) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_DELETING);
        }
        try (var hooks = BeforeHookContext.open(dbName, collName, EventType.DELETED, actingUser)) {
            final var outcome = hooks.apply(document, request.get_id(), OperationType.DELETE);
            return outcome.isRejected() ? outcome.rejection() : null;
        }
    }

    private static String idOf(JsonObject object) {
        return object.has(Globals.PK_FIELD) && object.get(Globals.PK_FIELD).isJsonString()
                ? object.get(Globals.PK_FIELD).asJsonString().getValue()
                : null;
    }
}
