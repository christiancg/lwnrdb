package org.techhouse.simplejs.internal;

import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.values.JsRegExp;

/**
 * Entry point from the interpreter/builtins into the regex subsystem: parses and compiles an
 * ECMA-262 {@code Pattern} via {@link RegexParser}, which builds the AST {@code RegexMatcher}
 * executes directly (see {@code internal.regex} package docs for why this exists instead of
 * translating to {@code java.util.regex} syntax).
 */
public final class RegexTranslator {
    private RegexTranslator() {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        final var program = RegexParser.compile(source, normalizedFlags);
        return new JsRegExp(source, normalizedFlags, program);
    }
}
