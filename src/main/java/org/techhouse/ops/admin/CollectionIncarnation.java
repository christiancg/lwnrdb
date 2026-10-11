package org.techhouse.ops.admin;

import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;

public final class CollectionIncarnation {
    private static final Cache cache = IocContainer.get(Cache.class);

    private CollectionIncarnation() {
    }

    public static long current(String dbName, String collName) {
        final var entry = cache.getAdminCollectionEntry(dbName, collName);
        return entry == null ? 0L : entry.getIncarnation();
    }

    public static boolean isCurrent(String dbName, String collName, long incarnation) {
        final var entry = cache.getAdminCollectionEntry(dbName, collName);
        return entry != null && sameLife(incarnation, entry.getIncarnation());
    }

    public static boolean sameLife(long recorded, long registered) {
        return recorded == 0L || registered == 0L || recorded == registered;
    }
}
