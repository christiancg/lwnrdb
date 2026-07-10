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
 * Durable two-phase-commit recovery log for Phase 5b, stored as marker records in the already-wired
 * {@code admin/transactions} collection (a participant's PREPARED marker and the coordinator's COMMIT
 * decision). Markers live beside the transaction's buffered slice ops and are removed by the same helpers.
 * The coordinator marker's presence is the commit point: present ⇒ committed, absent ⇒ presumed-abort.
 */
public final class Tx2pcLog {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final String COORDINATOR_ADDRESS_FIELD = "coordinatorAddress";
    private static final String COLLECTIONS_FIELD = "collections";
    private static final String PARTICIPANTS_FIELD = "participants";

    private Tx2pcLog() {
    }

    private static String markerId(String dtxId, String suffix) {
        return dtxId + Globals.COLL_IDENTIFIER_SEPARATOR + suffix;
    }

    public static void recordParticipantPrepared(String dtxId, String coordinatorAddress, List<String> collections)
            throws Exception {
        final var payload = new JsonObject();
        payload.addProperty(COORDINATOR_ADDRESS_FIELD, coordinatorAddress);
        payload.add(COLLECTIONS_FIELD, stringArray(collections));
        AdminOperationHelper.saveTransactionOp(AdminTransactionEntry.marker(dtxId,
                AdminTransactionEntry.MARKER_PARTICIPANT, AdminTransactionEntry.OP_TYPE_PARTICIPANT_PREPARED, payload));
    }

    public static void recordCoordinatorCommit(String dtxId, List<String> participants) throws Exception {
        final var payload = new JsonObject();
        payload.add(PARTICIPANTS_FIELD, stringArray(participants));
        AdminOperationHelper.saveTransactionOp(AdminTransactionEntry.marker(dtxId,
                AdminTransactionEntry.MARKER_COORDINATOR, AdminTransactionEntry.OP_TYPE_COORDINATOR_COMMIT, payload));
    }

    public static void deleteParticipantMarker(String dtxId) throws Exception {
        AdminOperationHelper.deleteTransactionOps(List.of(markerId(dtxId, AdminTransactionEntry.MARKER_PARTICIPANT)));
    }

    public static void deleteCoordinatorMarker(String dtxId) throws Exception {
        AdminOperationHelper.deleteTransactionOps(List.of(markerId(dtxId, AdminTransactionEntry.MARKER_COORDINATOR)));
    }

    public static boolean isCommitted(String dtxId) {
        return cache.getPkIndexTransaction(markerId(dtxId, AdminTransactionEntry.MARKER_COORDINATOR)) != null;
    }

    public static boolean isPrepared(String dtxId) {
        return cache.getPkIndexTransaction(markerId(dtxId, AdminTransactionEntry.MARKER_PARTICIPANT)) != null;
    }

    public static List<String> preparedDtxIds() {
        return dtxIdsWithSuffix(AdminTransactionEntry.MARKER_PARTICIPANT);
    }

    public static List<String> committedDtxIds() {
        return dtxIdsWithSuffix(AdminTransactionEntry.MARKER_COORDINATOR);
    }

    public static ParticipantMarker readParticipantMarker(String dtxId) throws Exception {
        final var entries = AdminOperationHelper
                .readTransactionOps(List.of(markerId(dtxId, AdminTransactionEntry.MARKER_PARTICIPANT)));
        if (entries.isEmpty()) {
            return null;
        }
        final var payload = entries.getFirst().getPayload();
        return new ParticipantMarker(payload.get(COORDINATOR_ADDRESS_FIELD).asJsonString().getValue(),
                readStringArray(payload, COLLECTIONS_FIELD));
    }

    public static List<String> readCoordinatorParticipants(String dtxId) throws Exception {
        final var entries = AdminOperationHelper
                .readTransactionOps(List.of(markerId(dtxId, AdminTransactionEntry.MARKER_COORDINATOR)));
        if (entries.isEmpty()) {
            return List.of();
        }
        return readStringArray(entries.getFirst().getPayload(), PARTICIPANTS_FIELD);
    }

    // The buffered slice op ids for a transaction: keys of the form {dtxId}|{seq} (numeric trailing token),
    // excluding the marker records.
    public static List<String> sliceOpIds(String dtxId) {
        final var prefix = dtxId + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var result = new ArrayList<String>();
        for (final var key : cache.getTransactionPkIndexes().keySet()) {
            if (key.startsWith(prefix)) {
                final var suffix = key.substring(prefix.length());
                if (suffix.chars().allMatch(Character::isDigit)) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    private static List<String> dtxIdsWithSuffix(String suffix) {
        final var tail = Globals.COLL_IDENTIFIER_SEPARATOR + suffix;
        final var result = new ArrayList<String>();
        for (final var key : cache.getTransactionPkIndexes().keySet()) {
            if (key.endsWith(tail)) {
                result.add(key.substring(0, key.length() - tail.length()));
            }
        }
        return result;
    }

    private static JsonArray stringArray(List<String> values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(new JsonString(value));
        }
        return array;
    }

    private static List<String> readStringArray(JsonObject payload, String field) {
        final var result = new ArrayList<String>();
        for (final var element : payload.get(field).asJsonArray().asList()) {
            result.add(element.asJsonString().getValue());
        }
        return result;
    }

    public record ParticipantMarker(String coordinatorAddress, List<String> collections) {
    }
}
