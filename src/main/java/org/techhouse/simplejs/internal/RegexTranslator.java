package org.techhouse.simplejs.internal;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private static final String VALID_FLAGS = "dgimsuvy";

    private RegexTranslator() {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        validateFlags(normalizedFlags);
        try {
            final var pattern = Pattern.compile(inlineFlags(normalizedFlags) + source);
            return new JsRegExp(source, normalizedFlags, pattern);
        } catch (PatternSyntaxException syntax) {
            throw new SyntaxErrorException("Invalid regular expression: /" + source + "/: " + syntax.getMessage());
        }
    }

    private static void validateFlags(String flags) {
        for (var i = 0; i < flags.length(); i++) {
            final var flag = flags.charAt(i);
            if (VALID_FLAGS.indexOf(flag) < 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
            if (flags.indexOf(flag, i + 1) >= 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
        }
        if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
            throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
        }
    }

    private static String inlineFlags(String flags) {
        final var inline = new StringBuilder();
        if (flags.indexOf('i') >= 0) {
            inline.append('i');
        }
        if (flags.indexOf('m') >= 0) {
            inline.append('m');
        }
        if (flags.indexOf('s') >= 0) {
            inline.append('s');
        }
        return inline.isEmpty() ? "" : "(?" + inline + ")";
    }
}
