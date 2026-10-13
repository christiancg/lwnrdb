package org.techhouse.ops;

import java.util.function.Predicate;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.utils.JsonUtils;

public class CompiledProcedureCache {
    private final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private final CompiledProgramLru<CompiledScript> cache = new CompiledProgramLru<>();

    public CompiledScript get(String dbName, String name, long version, String source) {
        if (cache.isDisabled()) {
            return simpleJs.compile(source, false);
        }
        final var key = keyOf(dbName, name, version);
        final var sourceHash = JsonUtils.sha256(source);
        final Predicate<CompiledScript> sameSource = compiled -> sourceHash.equals(compiled.sourceHash());
        final var cached = cache.get(key, sameSource);
        if (cached != null) {
            return cached;
        }
        return cache.admit(key, simpleJs.compile(source, false), source, sameSource);
    }

    public void invalidateDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        cache.removeIf(key -> key.startsWith(prefix));
    }

    public void invalidateProcedure(String dbName, String name) {
        final var prefix = Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR;
        cache.removeIf(key -> key.startsWith(prefix));
    }

    public int size() {
        return cache.size();
    }

    public long weightedBytes() {
        return cache.weightedBytes();
    }

    private static String keyOf(String dbName, String name, long version) {
        return Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
