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

// Field indexes are maintained asynchronously: an index-backed read must drop the ids still pending and
// re-derive them from current committed values, or a stale entry answers with a false positive.
public final class PendingWriteReconciler {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    private PendingWriteReconciler() {
    }

    // Call this AFTER the index read: a write that committed before the read is either already indexed
    // or still pending here; snapshotting first would miss one that landed in between.
    public static Set<String> pendingIds(String dbName, String collName) {
        return pendingIndexWrites.idsFor(dbName, collName);
    }

    public static List<DbEntry> pendingDocuments(String dbName, String collName, Set<String> pendingIds)
            throws IOException {
        return cache.getEntriesByIds(dbName, collName, pendingIds);
    }

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
