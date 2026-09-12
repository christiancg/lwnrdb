package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.BeforeHookContext;
import org.techhouse.ops.BeforeHookOutcome;
import org.techhouse.ops.EntrySizeGuard;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.resp.OperationResponse;

public final class TransactionWrites {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    private TransactionWrites() {
    }

    public static String ensureId(JsonObject object, String requestId) {
        var id = requestId;
        if (id == null) {
            id = object.has(Globals.PK_FIELD)
                    ? object.get(Globals.PK_FIELD).asJsonString().getValue()
                    : UUID.randomUUID().toString();
        }
        object.addProperty(Globals.PK_FIELD, id);
        return id;
    }

    // Hooks run once the session holds the collection lock, so they see the same serialization a
    // non-transactional write does.
    public static BeforeHookOutcome runBeforeHooks(String dbName, String collName, EventType event, String actingUser,
            JsonObject object, String id, OperationType type) {
        if (!BeforeHookContext.hasHooksFor(dbName, collName, event)) {
            return BeforeHookOutcome.accepted(object);
        }
        try (var hooks = BeforeHookContext.open(dbName, collName, event, actingUser)) {
            return hooks.apply(object, id, type);
        }
    }

    // Only re-checked when a hook replaced the document: a hook can inflate one past maxEntrySize.
    public static OperationResponse checkEntrySize(String dbName, String collName, JsonObject effective,
            JsonObject original, OperationType type) {
        if (effective == original) {
            return null;
        }
        return EntrySizeGuard.check(dbName, collName, effective, type);
    }

    public static OperationResponse runDeleteBeforeHooks(Transaction transaction, String collId, String dbName,
            String collName, String id) {
        if (!BeforeHookContext.hasHooksFor(dbName, collName, EventType.DELETED)) {
            return null;
        }
        final var document = effectiveDocumentFor(transaction, collId, dbName, collName, id);
        if (document == null) {
            return null;
        }
        try (var hooks = BeforeHookContext.open(dbName, collName, EventType.DELETED,
                clientTracker.getAuthenticatedUsername(transaction.getClientId()))) {
            final var outcome = hooks.apply(document, id, OperationType.DELETE);
            return outcome.isRejected() ? outcome.rejection() : null;
        }
    }

    public static JsonObject effectiveDocumentFor(Transaction transaction, String collId, String dbName,
            String collName, String id) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            final var buffered = overlay.get(id);
            return Transaction.isTombstone(buffered) ? null : buffered;
        }
        try {
            final var entries = cache.getEntriesByIds(dbName, collName, Set.of(id));
            return entries.isEmpty() ? null : entries.getFirst().getData();
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonObject documentForDeletedTrigger(Transaction transaction, String collId, String dbName,
            String collName, String id) {
        final var depth = transaction.getTriggerDepth();
        if (!TriggerHelper.firesOnDelete(dbName, collName, depth)) {
            return null;
        }
        final var overlay = transaction.overlayFor(collId);
        final var buffered = overlay == null ? null : overlay.get(id);
        if (buffered != null && !Transaction.isTombstone(buffered)) {
            return buffered;
        }
        final var captured = TriggerHelper.captureForDelete(dbName, collName, id, depth);
        return captured == null ? null : captured.getData();
    }

    public static boolean isVisible(Transaction transaction, String collId, List<PkIndexEntry> primaryKeyIndex,
            String id) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            return !Transaction.isTombstone(overlay.get(id));
        }
        return Collections.binarySearch(primaryKeyIndex, id) >= 0;
    }
}
