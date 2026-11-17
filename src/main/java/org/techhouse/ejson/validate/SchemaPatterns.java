package org.techhouse.ejson.validate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final Map<String, CachedProgram> COMPILED = new ConcurrentHashMap<>();
    private static final ReentrantLock ADMISSION_LOCK = new ReentrantLock();
    private static volatile long admissionEpoch;
    private static long cachedBytes;

    private static final class CachedProgram {
        private final RegexProgram program;
        private final long weight;
        private volatile long lastUsedEpoch;

        private CachedProgram(RegexProgram program, long weight, long epoch) {
            this.program = program;
            this.weight = weight;
            this.lastUsedEpoch = epoch;
        }

        long weight() {
            return weight;
        }

        RegexProgram touch() {
            final var epoch = admissionEpoch;
            if (lastUsedEpoch != epoch) {
                lastUsedEpoch = epoch;
            }
            return program;
        }
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
        return RegexMatcher.matches(program, input, budget);
    }

    private static RegexProgram compile(String regex) {
        final var cached = COMPILED.get(regex);
        if (cached != null) {
            return cached.touch();
        }
        return admit(regex, RegexParser.compile(regex, ECMA_UNICODE_MODE));
    }

    private static RegexProgram admit(String regex, RegexProgram program) {
        final var maxBytes = configuration.sizeValue(ConfigKey.SCHEMA_PATTERN_CACHE_MAX_BYTES);
        final var weight = (long) Character.BYTES * regex.length() + program.estimatedBytes();
        if (regex.length() > configuration.intValue(ConfigKey.SCHEMA_MAX_PATTERN_LENGTH) || weight > maxBytes) {
            return program;
        }
        ADMISSION_LOCK.lock();
        try {
            final var raced = COMPILED.get(regex);
            if (raced != null) {
                return raced.touch();
            }
            final var epoch = admissionEpoch + 1;
            admissionEpoch = epoch;
            COMPILED.put(regex, new CachedProgram(program, weight, epoch));
            cachedBytes += weight;
            var evicted = true;
            while (evicted && (COMPILED.size() > MAX_CACHED_PATTERNS || cachedBytes > maxBytes)) {
                evicted = evictLeastRecentlyUsed(regex);
            }
        } finally {
            ADMISSION_LOCK.unlock();
        }
        return program;
    }

    private static boolean evictLeastRecentlyUsed(String admitted) {
        String eldestKey = null;
        CachedProgram eldest = null;
        for (final var entry : COMPILED.entrySet()) {
            final var candidate = entry.getValue();
            if (!entry.getKey().equals(admitted)
                    && (eldest == null || candidate.lastUsedEpoch < eldest.lastUsedEpoch)) {
                eldestKey = entry.getKey();
                eldest = candidate;
            }
        }
        if (eldest == null) {
            return false;
        }
        COMPILED.remove(eldestKey);
        cachedBytes -= eldest.weight();
        return true;
    }
}
