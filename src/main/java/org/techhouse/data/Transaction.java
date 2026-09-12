package org.techhouse.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.techhouse.ejson.elements.JsonObject;

public class Transaction {
    private static final JsonObject TOMBSTONE = new JsonObject();

    private final UUID transactionId;
    private final UUID clientId;
    private int seq;
    private final Set<String> heldLocks = new HashSet<>();
    // Carried so a trigger's own transaction cannot restart the cascade at zero and loop forever.
    private int triggerDepth;
    private final List<String> bufferedOpIds = new ArrayList<>();
    // LinkedHashMap, not HashMap: inserts must stream in a stable order after the committed documents.
    private final Map<String, LinkedHashMap<String, JsonObject>> overlay = new HashMap<>();
    private final Map<Long, Set<String>> insertedIdsByOp = new HashMap<>();

    public Transaction(UUID transactionId, UUID clientId) {
        this.transactionId = transactionId;
        this.clientId = clientId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public int getTriggerDepth() {
        return triggerDepth;
    }

    public void setTriggerDepth(int triggerDepth) {
        this.triggerDepth = triggerDepth;
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

    // Decided at buffer time: after the commit applies, the store can no longer tell an insert from an
    // update, and that is what decides whether the write fires CREATED or UPDATED.
    public void recordInserts(long seq, Collection<String> ids) {
        if (!ids.isEmpty()) {
            insertedIdsByOp.put(seq, Set.copyOf(ids));
        }
    }

    public Set<String> insertedIdsFor(long seq) {
        return insertedIdsByOp.getOrDefault(seq, Set.of());
    }

    public void recordSave(String collId, String id, JsonObject doc) {
        overlay.computeIfAbsent(collId, _ -> new LinkedHashMap<>()).put(id, doc.deepCopy());
    }

    public void recordDelete(String collId, String id) {
        overlay.computeIfAbsent(collId, _ -> new LinkedHashMap<>()).put(id, TOMBSTONE);
    }

    public Map<String, JsonObject> overlayFor(String collId) {
        return overlay.get(collId);
    }

    public Set<String> touchedCollections() {
        return overlay.keySet();
    }

    public static boolean isTombstone(JsonObject doc) {
        return doc == TOMBSTONE;
    }
}
