package org.techhouse.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.data.DbEntry;

final class ResidentDocuments {
    private final ConcurrentHashMap<String, DbEntry> documents;
    private final SizeCell cell;

    ResidentDocuments(SizeCell cell) {
        this(new ConcurrentHashMap<>(), cell);
    }

    private ResidentDocuments(ConcurrentHashMap<String, DbEntry> documents, SizeCell cell) {
        this.documents = documents;
        this.cell = cell;
    }

    static ResidentDocuments attach(ConcurrentHashMap<String, DbEntry> collected, SizeCell cell, long measuredBytes) {
        cell.set(measuredBytes);
        return new ResidentDocuments(collected, cell);
    }

    Map<String, DbEntry> documents() {
        return documents;
    }

    DbEntry get(String id) {
        return documents.get(id);
    }

    boolean containsKey(String id) {
        return documents.containsKey(id);
    }

    int size() {
        return documents.size();
    }

    void put(DbEntry entry) {
        put(entry.get_id(), entry);
    }

    void put(String id, DbEntry entry) {
        final var previous = documents.put(id, entry);
        cell.add(CacheSizeEstimator.estimateCollectionEntry(entry)
                - (previous == null ? 0L : CacheSizeEstimator.estimateCollectionEntry(previous)));
    }

    void remove(String id) {
        final var removed = documents.remove(id);
        if (removed != null) {
            cell.add(-CacheSizeEstimator.estimateCollectionEntry(removed));
        }
    }

    long bytes() {
        return cell.bytes();
    }

    void retire() {
        cell.retire();
    }
}
