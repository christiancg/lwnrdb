package org.techhouse.cache;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.techhouse.config.Globals;

// Misses live in a cache separate from the values, so a caller naming thousands of nonexistent
// definitions cannot evict the ones in use.
final class DefinitionCache<T> {
    private final Supplier<BoundedLruCache<T>> values;
    private final Supplier<BoundedLruCache<Boolean>> misses;
    private final String missPrefix;
    private final BiFunction<String, String, T> loader;
    private final AtomicLong generation = new AtomicLong();

    // Suppliers, not direct references: AdminCache owns these caches and may replace an instance.
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
        final var generationAtLoad = generation.get();
        final var loaded = loader.apply(dbName, name);
        if (generation.get() != generationAtLoad) {
            return loaded;
        }
        if (loaded == null) {
            misses.get().put(missPrefix + id, Boolean.TRUE);
        } else {
            values.get().put(id, loaded);
        }
        return loaded;
    }

    void put(String id, T value) {
        generation.incrementAndGet();
        misses.get().remove(missPrefix + id);
        values.get().put(id, value);
    }

    void remove(String id) {
        generation.incrementAndGet();
        values.get().remove(id);
        misses.get().remove(missPrefix + id);
    }

    void removeForDatabase(String dbName) {
        generation.incrementAndGet();
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        values.get().removeIf(id -> id.startsWith(prefix));
        misses.get().removeIf(id -> id.startsWith(missPrefix + prefix));
    }

    void removeIf(Predicate<String> keyMatches) {
        generation.incrementAndGet();
        values.get().removeIf(keyMatches);
    }
}
