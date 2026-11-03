package org.techhouse.ops.index;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.FieldPath;

public final class FieldMaintenance {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private FieldMaintenance() {
    }

    public static List<DeferredFold> maintain(String dbName, String collName, String fieldName, Collection<String> ids,
            Map<String, DbEntry> byId, Map<String, List<IndexedValue>> priors)
            throws IOException, InterruptedException {
        return maintain(dbName, collName, fieldName, ids, byId, priors, null);
    }

    static List<DeferredFold> maintain(String dbName, String collName, String fieldName, Collection<String> ids,
            Map<String, DbEntry> byId, Map<String, List<IndexedValue>> priors, HeldTypeLists held)
            throws IOException, InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        var flushed = false;
        try {
            final var batch = FieldIndexBatch.open(dbName, collName, fieldName, ids, priors, held);
            final var path = FieldPath.of(fieldName);
            for (var id : ids) {
                final var doc = byId.get(id);
                batch.apply(id, doc == null ? null : path.valueIn(doc.getData()));
            }
            final var folds = batch.flushLeavingFolds();
            if (held != null) {
                held.chunkFlushed();
            }
            flushed = true;
            return folds;
        } finally {
            if (!flushed) {
                cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
                if (held != null) {
                    held.clear();
                }
            }
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }
}
