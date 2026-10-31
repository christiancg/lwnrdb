package org.techhouse.ops;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.IndexedValue;
import org.techhouse.utils.JsonUtils;

final class PriorIndexedValues {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private PriorIndexedValues() {
    }

    static Set<String> fieldsOf(String dbName, String collName) {
        return cache.fieldsKeepingPriorValues(dbName, collName);
    }

    static Map<String, IndexedValue> of(Set<String> fields, DbEntry previous) {
        if (fields.isEmpty()) {
            return null;
        }
        final var priors = new HashMap<String, IndexedValue>(fields.size() * 2);
        try {
            for (final var field : fields) {
                priors.put(field,
                        previous == null ? null : IndexedValue.of(JsonUtils.getFromPath(previous.getData(), field)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return priors;
    }

    static Map<String, IndexedValue> ofStored(Set<String> fields, String dbName, String collName,
            PkIndexEntry previousRow) {
        if (fields.isEmpty()) {
            return null;
        }
        try {
            return of(fields, cache.getById(dbName, collName, previousRow));
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Map<String, IndexedValue>> ofRows(Set<String> fields, List<PkIndexEntry> previousRows,
            Collection<String> insertedIds) {
        if (fields.isEmpty()) {
            return null;
        }
        final var byId = new HashMap<String, Map<String, IndexedValue>>();
        if (!previousRows.isEmpty()) {
            try {
                for (final var previous : fs.getByIndexEntries(previousRows)) {
                    byId.put(previous.get_id(), of(fields, previous));
                }
            } catch (IOException e) {
                return null;
            }
        }
        final var none = of(fields, null);
        for (final var id : insertedIds) {
            byId.put(id, none);
        }
        return byId;
    }
}
