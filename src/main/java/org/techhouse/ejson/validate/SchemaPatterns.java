package org.techhouse.ejson.validate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.ejson.exceptions.InvalidSchemaException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;

final class SchemaPatterns {
    private static final String ECMA_UNICODE_MODE = "u";
    private static final Map<String, RegexProgram> COMPILED = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_PATTERNS = 256;

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

    static boolean matches(String regex, String input) {
        final RegexProgram program;
        try {
            program = compile(regex);
        } catch (SyntaxErrorException | IllegalStateException e) {
            throw new InvalidSchemaException("Invalid ECMA-262 regular expression in schema: " + regex);
        }
        return RegexMatcher.exec(program, input, 0, false) != null;
    }

    private static RegexProgram compile(String regex) {
        final var cached = COMPILED.get(regex);
        if (cached != null) {
            return cached;
        }
        final var program = RegexParser.compile(regex, ECMA_UNICODE_MODE);
        if (COMPILED.size() >= MAX_CACHED_PATTERNS) {
            return program;
        }
        final var raced = COMPILED.putIfAbsent(regex, program);
        return raced != null ? raced : program;
    }
}
