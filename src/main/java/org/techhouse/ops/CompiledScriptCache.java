package org.techhouse.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.utils.JsonUtils;

public class CompiledScriptCache {
    private final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, CachedCompilation> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();

    public record CachedCompilation(CompiledScript compiled, RuntimeException failure) {
    }

    public CachedCompilation get(String source) {
        final var maxSize = configuration.getProcedureCacheSize();
        if (maxSize <= 0) {
            return compile(source);
        }
        final var key = JsonUtils.sha256(source);
        lock.lock();
        try {
            var entry = cache.get(key);
            if (entry == null) {
                entry = compile(source);
                cache.put(key, entry);
                evictDownTo(maxSize);
            }
            return entry;
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

    private CachedCompilation compile(String source) {
        try {
            return new CachedCompilation(simpleJs.compile(source, false), null);
        } catch (RuntimeException failure) {
            return new CachedCompilation(null, failure);
        }
    }

    private void evictDownTo(int maxSize) {
        final var iterator = cache.keySet().iterator();
        while (cache.size() > maxSize && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
