package org.techhouse.ejson.validate;

import java.util.LinkedHashMap;
import java.util.Map;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.exceptions.InvalidSchemaException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

final class SchemaPatterns {
    private static final int MAX_CACHED_PATTERNS = 4096;
    private static final String ECMA_UNICODE_MODE = "u";
    private static final Configuration configuration = Configuration.getInstance();
    private static final Map<String, CachedProgram> COMPILED = new LinkedHashMap<>(64, 0.75f, true);
    private static long cachedBytes;

    private record CachedProgram(RegexProgram program, long weight) {
    }

    private SchemaPatterns() {
    }

    static boolean doesNotCompile(String regex) {
        try {
            compile(regex);
            return false;
        } catch (SyntaxErrorException | IllegalStateException e) {
            return true;
        }
    }

    static boolean matches(String regex, String input, RegexStepBudget budget) {
        final RegexProgram program;
        try {
            program = compile(regex);
        } catch (SyntaxErrorException | IllegalStateException e) {
            throw new InvalidSchemaException("Invalid ECMA-262 regular expression in schema: " + regex);
        }
        return RegexMatcher.exec(program, input, 0, false, budget) != null;
    }

    private static RegexProgram compile(String regex) {
        final var cached = cachedProgram(regex);
        if (cached != null) {
            return cached;
        }
        return admit(regex, RegexParser.compile(regex, ECMA_UNICODE_MODE));
    }

    private static synchronized RegexProgram cachedProgram(String regex) {
        final var cached = COMPILED.get(regex);
        return cached == null ? null : cached.program();
    }

    private static synchronized RegexProgram admit(String regex, RegexProgram program) {
        final var maxBytes = configuration.sizeValue(ConfigKey.SCHEMA_PATTERN_CACHE_MAX_BYTES);
        final var weight = (long) Character.BYTES * regex.length() + program.estimatedBytes();
        if (regex.length() > configuration.intValue(ConfigKey.SCHEMA_MAX_PATTERN_LENGTH) || weight > maxBytes) {
            return program;
        }
        final var raced = COMPILED.putIfAbsent(regex, new CachedProgram(program, weight));
        if (raced != null) {
            return raced.program();
        }
        cachedBytes += weight;
        final var eldest = COMPILED.values().iterator();
        while (COMPILED.size() > MAX_CACHED_PATTERNS || cachedBytes > maxBytes) {
            cachedBytes -= eldest.next().weight();
            eldest.remove();
        }
        return program;
    }
}
