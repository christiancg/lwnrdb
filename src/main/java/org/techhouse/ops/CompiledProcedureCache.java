package org.techhouse.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;

/**
 * Keeps the parsed form of a stored procedure so a repeated call does not re-lex and re-parse its source.
 *
 * <p>
 * The key carries the procedure's version, which is what makes the cache correct with no invalidation hook on
 * any write path: a save bumps the version, so a stale entry can never be served, including when the save
 * happened on another node and arrived by replication or by an admin anti-entropy conform.
 */
public class CompiledProcedureCache {
    private final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, CompiledScript> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();

    public CompiledScript get(String dbName, String name, long version, String source) {
        final var maxSize = configuration.getProcedureCacheSize();
        if (maxSize <= 0) {
            return simpleJs.compile(source, false);
        }
        final var key = keyOf(dbName, name, version);
        lock.lock();
        try {
            var compiled = cache.get(key);
            if (compiled == null) {
                compiled = simpleJs.compile(source, false);
                cache.put(key, compiled);
                evictDownTo(maxSize);
            }
            return compiled;
        } finally {
            lock.unlock();
        }
    }

    public void invalidateDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        lock.lock();
        try {
            cache.keySet().removeIf(key -> key.startsWith(prefix));
        } finally {
            lock.unlock();
        }
    }

    public void invalidateProcedure(String dbName, String name) {
        final var prefix = Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR;
        lock.lock();
        try {
            cache.keySet().removeIf(key -> key.startsWith(prefix));
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    // The map is in access order, so the iterator's first entry is the least recently used.
    private void evictDownTo(int maxSize) {
        final var iterator = cache.keySet().iterator();
        while (cache.size() > maxSize && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String keyOf(String dbName, String name, long version) {
        return Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
