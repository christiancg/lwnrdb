package org.techhouse.ejson.validate;

import java.util.LinkedHashMap;
import java.util.Map;
import org.techhouse.ejson.exceptions.InvalidSchemaException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

final class SchemaPatterns {
    private static final int MAX_CACHED_PATTERNS = 4096;
    private static final long MAX_CACHED_PATTERN_CHARS = 4L * 1024 * 1024;
    private static final String ECMA_UNICODE_MODE = "u";
    private static final Map<String, RegexProgram> COMPILED = new LinkedHashMap<>(64, 0.75f, true);
    private static long cachedChars;

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
        return COMPILED.get(regex);
    }

    private static synchronized RegexProgram admit(String regex, RegexProgram program) {
        if (regex.length() > MAX_CACHED_PATTERN_CHARS) {
            return program;
        }
        final var raced = COMPILED.putIfAbsent(regex, program);
        if (raced != null) {
            return raced;
        }
        cachedChars += regex.length();
        final var eldest = COMPILED.entrySet().iterator();
        while (COMPILED.size() > MAX_CACHED_PATTERNS || cachedChars > MAX_CACHED_PATTERN_CHARS) {
            final var evicted = eldest.next();
            cachedChars -= evicted.getKey().length();
            eldest.remove();
        }
        return program;
    }
}
