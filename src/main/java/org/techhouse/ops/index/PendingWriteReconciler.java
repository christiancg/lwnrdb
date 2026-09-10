package org.techhouse.ops.index;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

// Field indexes are maintained asynchronously, so a committed write can be absent from the index it
// belongs in. Every index-backed read closes that gap the same way: drop the ids still pending, then
// re-derive them from the documents' current committed values. Skipping the drop leaves the stale
// index entry answering, which is a false positive the index-consistency contract does not allow.
public final class PendingWriteReconciler {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    private PendingWriteReconciler() {
    }

    // Call this AFTER the index read. A write that committed before the read is either already indexed
    // (so the index answered accurately) or still pending (so it is corrected here); snapshotting first
    // would miss one that landed in between.
    public static Set<String> pendingIds(String dbName, String collName) {
        return pendingIndexWrites.idsFor(dbName, collName);
    }

    // The pending documents at their current committed value, read in one batch.
    public static List<DbEntry> pendingDocuments(String dbName, String collName, Set<String> pendingIds)
            throws IOException {
        return cache.getEntriesByIds(dbName, collName, pendingIds);
    }

    // Corrects an id set the index produced: a pending document counts only if its current value still
    // satisfies the caller's test, so a stale entry can neither add nor hide a match.
    public static Set<String> correctIds(Set<String> indexed, String dbName, String collName, Set<String> pendingIds,
            String fieldName, BiPredicate<JsonObject, String> matches) throws IOException {
        final var corrected = new HashSet<>(indexed);
        corrected.removeAll(pendingIds);
        for (final var dbEntry : pendingDocuments(dbName, collName, pendingIds)) {
            if (matches.test(dbEntry.getData(), fieldName)) {
                corrected.add(dbEntry.get_id());
            }
        }
        return corrected;
    }
}
