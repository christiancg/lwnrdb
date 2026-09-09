package org.techhouse.cache;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.techhouse.config.Globals;

// One disk-derived definition family (schemas, procedures, schedules): loaded lazily on first access
// and negatively cached on absence, so a name that does not exist on disk is read once rather than on
// every call. Misses live in a cache separate from the values, so a caller naming thousands of
// nonexistent definitions cannot evict the ones in use.
final class DefinitionCache<T> {
    private final Supplier<BoundedLruCache<T>> values;
    private final Supplier<BoundedLruCache<Boolean>> misses;
    private final String missPrefix;
    private final BiFunction<String, String, T> loader;

    // The caches are resolved per call rather than captured: AdminCache owns them, so reading them
    // through it keeps one source of truth even when an instance is replaced underneath.
    DefinitionCache(Supplier<BoundedLruCache<T>> values, Supplier<BoundedLruCache<Boolean>> misses, String missPrefix,
            BiFunction<String, String, T> loader) {
        this.values = values;
        this.misses = misses;
        this.missPrefix = missPrefix;
        this.loader = loader;
    }

    T get(String dbName, String name) {
        final var id = Cache.getCollectionIdentifier(dbName, name);
        final var cached = values.get().get(id);
        if (cached != null) {
            return cached;
        }
        if (misses.get().get(missPrefix + id) != null) {
            return null;
        }
        final var loaded = loader.apply(dbName, name);
        if (loaded == null) {
            misses.get().put(missPrefix + id, Boolean.TRUE);
        } else {
            values.get().put(id, loaded);
        }
        return loaded;
    }

    void put(String id, T value) {
        misses.get().remove(missPrefix + id);
        values.get().put(id, value);
    }

    void remove(String id) {
        values.get().remove(id);
        misses.get().remove(missPrefix + id);
    }

    void removeForDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        values.get().removeIf(id -> id.startsWith(prefix));
        misses.get().removeIf(id -> id.startsWith(missPrefix + prefix));
    }

    void removeIf(Predicate<String> keyMatches) {
        values.get().removeIf(keyMatches);
    }
}
