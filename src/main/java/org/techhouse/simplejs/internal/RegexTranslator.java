package org.techhouse.simplejs.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private RegexTranslator() {
    }

    private static final Map<ProgramKey, RegexProgram> PROGRAMS = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_PROGRAMS = 512;

    private record ProgramKey(String source, String flags) {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        return new JsRegExp(source, normalizedFlags, programFor(source, normalizedFlags));
    }

    private static RegexProgram programFor(String source, String flags) {
        final var key = new ProgramKey(source, flags);
        final var cached = PROGRAMS.get(key);
        if (cached != null) {
            return cached;
        }
        final var program = RegexParser.compile(source, flags);
        if (PROGRAMS.size() >= MAX_CACHED_PROGRAMS) {
            return program;
        }
        final var raced = PROGRAMS.putIfAbsent(key, program);
        return raced != null ? raced : program;
    }
}
