package org.techhouse.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.techhouse.ejson.elements.JsonObject;

/**
 * Per-connection state for an in-progress transaction. Buffered writes are persisted as operation
 * records in the {@code admin/transactions} collection (durable source of truth), while this object
 * holds the fast-access state the connection thread needs: the collection write locks it currently
 * holds (acquired lazily on first write to each collection and released at commit/rollback), the ids
 * of the buffered operation records, and an in-memory overlay of the buffered mutations used to
 * serve read-your-writes for the transaction's own reads.
 */
public class Transaction {
    // Sentinel stored in the overlay to represent a buffered delete (a tombstone hiding the committed
    // document from the transaction's own reads until commit).
    private static final JsonObject TOMBSTONE = new JsonObject();

    private final UUID transactionId;
    private final UUID clientId;
    private int seq;
    private final Set<String> heldLocks = new HashSet<>();
    private final List<String> bufferedOpIds = new ArrayList<>();
    // collId -> (id -> buffered document | TOMBSTONE). LinkedHashMap keeps insertion order so pure
    // inserts stream in a stable order after the committed documents during an aggregation read.
    private final Map<String, LinkedHashMap<String, JsonObject>> overlay = new HashMap<>();

    public Transaction(UUID transactionId, UUID clientId) {
        this.transactionId = transactionId;
        this.clientId = clientId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public int nextSeq() {
        return seq++;
    }

    public boolean holdsLock(String collId) {
        return heldLocks.contains(collId);
    }

    public void addHeldLock(String collId) {
        heldLocks.add(collId);
    }

    public Set<String> getHeldLocks() {
        return heldLocks;
    }

    public void addBufferedOpId(String opId) {
        bufferedOpIds.add(opId);
    }

    public List<String> getBufferedOpIds() {
        return bufferedOpIds;
    }

    public void recordSave(String collId, String id, JsonObject doc) {
        overlay.computeIfAbsent(collId, _ -> new LinkedHashMap<>()).put(id, doc.deepCopy());
    }

    public void recordDelete(String collId, String id) {
        overlay.computeIfAbsent(collId, _ -> new LinkedHashMap<>()).put(id, TOMBSTONE);
    }

    // The buffered mutations for a collection (id -> document | TOMBSTONE), or null if the transaction
    // has not written to that collection.
    public Map<String, JsonObject> overlayFor(String collId) {
        return overlay.get(collId);
    }

    // The collection identifiers the transaction has buffered writes for.
    public Set<String> touchedCollections() {
        return overlay.keySet();
    }

    public static boolean isTombstone(JsonObject doc) {
        return doc == TOMBSTONE;
    }
}
