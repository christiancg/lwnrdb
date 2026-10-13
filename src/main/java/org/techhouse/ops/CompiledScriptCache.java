package org.techhouse.ops;

import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.CompiledScript;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

public class CompiledScriptCache {
    public static final String NESTING_TOO_DEEP = "The script is nested too deeply to compile";
    private final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private final CompiledProgramLru<CachedCompilation> cache = new CompiledProgramLru<>();

    public record CachedCompilation(CompiledScript compiled, RuntimeException failure) {
    }

    public CachedCompilation get(String source) {
        if (cache.isDisabled()) {
            return compile(source);
        }
        final var cached = cache.get(source, _ -> true);
        if (cached != null) {
            return cached;
        }
        return cache.admit(source, compile(source), source, _ -> true);
    }

    public int size() {
        return cache.size();
    }

    public long weightedBytes() {
        return cache.weightedBytes();
    }

    private CachedCompilation compile(String source) {
        try {
            return new CachedCompilation(simpleJs.compile(source, false), null);
        } catch (RuntimeException failure) {
            return new CachedCompilation(null, failure);
        } catch (StackOverflowError tooDeep) {
            return new CachedCompilation(null, new SyntaxErrorException(NESTING_TOO_DEEP));
        }
    }
}
