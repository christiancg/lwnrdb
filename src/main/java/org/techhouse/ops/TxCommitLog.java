package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

/**
 * Durable commit-intent log for single-node transactions, stored as a {@code {txId}|localcommit} marker in the
 * already-wired {@code admin/transactions} collection.
 *
 * <p>
 * The distributed path has had this since Phase 5b — {@link Tx2pcLog}'s coordinator marker is its commit
 * point and {@code commitPreparedFromDurable} finishes a decided commit after a crash. The single-node
 * {@code commit()} had no equivalent, so recovery could not tell a transaction that was still buffering
 * (discard) from one that was mid-commit (finish), and discarded both — leaving a partially applied
 * transaction. Writing this marker before the first op is applied is what makes that distinction durable.
 */
public final class TxCommitLog {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final String COLLECTIONS_FIELD = "collections";
    private static final String OP_IDS_FIELD = "opIds";
    private static final String COMMITTED_AT_FIELD = "committedAt";

    public record LocalCommitMarker(List<String> opIds, List<String> collections, long committedAt) {
    }

    private TxCommitLog() {
    }

    private static String markerId(String txId) {
        return txId + Globals.COLL_IDENTIFIER_SEPARATOR + AdminTransactionEntry.MARKER_LOCAL_COMMIT;
    }

    public static void recordLocalCommit(String txId, List<String> opIds, List<String> collections) throws Exception {
        final var payload = new JsonObject();
        payload.add(OP_IDS_FIELD, stringArray(opIds));
        payload.add(COLLECTIONS_FIELD, stringArray(collections));
        payload.addProperty(COMMITTED_AT_FIELD, Long.toString(System.currentTimeMillis()));
        AdminOperationHelper.saveTransactionOp(AdminTransactionEntry.marker(txId,
                AdminTransactionEntry.MARKER_LOCAL_COMMIT, AdminTransactionEntry.OP_TYPE_LOCAL_COMMIT, payload));
    }

    public static void clearLocalCommit(String txId) throws Exception {
        AdminOperationHelper.deleteTransactionOps(List.of(markerId(txId)));
    }

    public static boolean isLocallyCommitted(String txId) {
        return cache.getPkIndexTransaction(markerId(txId)) != null;
    }

    public static List<String> localCommitTxIds() {
        final var tail = Globals.COLL_IDENTIFIER_SEPARATOR + AdminTransactionEntry.MARKER_LOCAL_COMMIT;
        final var result = new ArrayList<String>();
        for (final var key : cache.getTransactionPkIndexes().keySet()) {
            if (key.endsWith(tail)) {
                result.add(key.substring(0, key.length() - tail.length()));
            }
        }
        return result;
    }

    public static LocalCommitMarker readLocalCommitMarker(String txId) throws Exception {
        final var entries = AdminOperationHelper.readTransactionOps(List.of(markerId(txId)));
        if (entries.isEmpty()) {
            return null;
        }
        final var payload = entries.getFirst().getPayload();
        final var committedAt = payload.has(COMMITTED_AT_FIELD)
                ? Long.parseLong(payload.get(COMMITTED_AT_FIELD).asJsonString().getValue())
                : 0L;
        return new LocalCommitMarker(readStringArray(payload, OP_IDS_FIELD),
                readStringArray(payload, COLLECTIONS_FIELD), committedAt);
    }

    private static JsonArray stringArray(List<String> values) {
        final var array = new JsonArray();
        if (values != null) {
            for (final var value : values) {
                array.add(new JsonString(value));
            }
        }
        return array;
    }

    private static List<String> readStringArray(JsonObject payload, String field) {
        final var result = new ArrayList<String>();
        if (!payload.has(field)) {
            return result;
        }
        for (final var element : payload.get(field).asJsonArray().asList()) {
            result.add(element.asJsonString().getValue());
        }
        return result;
    }
}
