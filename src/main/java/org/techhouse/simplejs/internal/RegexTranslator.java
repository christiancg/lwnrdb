package org.techhouse.simplejs.internal;

import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private RegexTranslator() {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        final var program = RegexParser.compile(source, normalizedFlags);
        return new JsRegExp(source, normalizedFlags, program);
    }
}
