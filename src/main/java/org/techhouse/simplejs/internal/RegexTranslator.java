package org.techhouse.simplejs.internal;

import java.util.LinkedHashMap;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private RegexTranslator() {
    }

    private static final int MAX_CACHED_SOURCE_CHARS_PER_PROGRAM = 4096;
    private static final long MAX_CACHED_SOURCE_CHARS = 1L << 20;
    private static final LinkedHashMap<ProgramKey, RegexProgram> PROGRAMS = new LinkedHashMap<>(16, 0.75f, true);
    private static long cachedSourceChars;

    private record ProgramKey(String source, String flags) {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        return new JsRegExp(source, normalizedFlags, programFor(source, normalizedFlags));
    }

    public static JsRegExp compile(String source, String flags, InterpreterOps ops) {
        InterpreterOps.paceWork(ops, source.length());
        final var parseBytes = source.length() * InterpreterOps.BYTES_PER_ELEMENT;
        InterpreterOps.charge(ops, parseBytes);
        try {
            return compile(source, flags);
        } finally {
            InterpreterOps.release(ops, parseBytes);
        }
    }

    private static RegexProgram programFor(String source, String flags) {
        final var key = new ProgramKey(source, flags);
        final var cached = cached(key);
        if (cached != null) {
            return cached;
        }
        final var program = RegexParser.compile(source, flags);
        if (source.length() <= MAX_CACHED_SOURCE_CHARS_PER_PROGRAM) {
            remember(key, program);
        }
        return program;
    }

    private static synchronized RegexProgram cached(ProgramKey key) {
        return PROGRAMS.get(key);
    }

    private static synchronized void remember(ProgramKey key, RegexProgram program) {
        if (PROGRAMS.putIfAbsent(key, program) != null) {
            return;
        }
        cachedSourceChars += key.source().length();
        final var eldest = PROGRAMS.entrySet().iterator();
        while (cachedSourceChars > MAX_CACHED_SOURCE_CHARS && eldest.hasNext()) {
            cachedSourceChars -= eldest.next().getKey().source().length();
            eldest.remove();
        }
    }
}
