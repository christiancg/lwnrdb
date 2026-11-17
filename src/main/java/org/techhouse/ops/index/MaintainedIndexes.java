package org.techhouse.ops.index;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class MaintainedIndexes {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private MaintainedIndexes() {
    }

    public static List<String> of(String dbName, String collName) {
        final var registered = cache.getIndexesForCollection(dbName, collName);
        final var maintained = new ArrayList<String>(registered.size());
        for (final var fieldName : registered) {
            if (!fs.indexMarkers().isMarked(dbName, collName, fieldName)) {
                maintained.add(fieldName);
            }
        }
        return maintained;
    }

    public static boolean none(String dbName, String collName) {
        for (final var fieldName : cache.getIndexesForCollection(dbName, collName)) {
            if (!fs.indexMarkers().isMarked(dbName, collName, fieldName)) {
                return false;
            }
        }
        return true;
    }
}
