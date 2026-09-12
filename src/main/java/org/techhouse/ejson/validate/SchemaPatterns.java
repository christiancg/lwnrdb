package org.techhouse.ejson.validate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class SchemaPatterns {
    private SchemaPatterns() {
    }

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_PATTERNS = 256;

    static Pattern compile(String regex) {
        final var cached = COMPILED.get(regex);
        if (cached != null) {
            return cached;
        }
        final var pattern = Pattern.compile(regex);
        if (COMPILED.size() >= MAX_CACHED_PATTERNS) {
            return pattern;
        }
        final var raced = COMPILED.putIfAbsent(regex, pattern);
        return raced != null ? raced : pattern;
    }
}
