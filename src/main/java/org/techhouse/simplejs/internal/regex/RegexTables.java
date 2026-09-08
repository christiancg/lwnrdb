package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexFlags.VALID_FLAGS;

import org.techhouse.simplejs.exceptions.SyntaxErrorException;

public final class RegexTables {
    public static final String SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|";

    static final String SET_SYNTAX_CHARACTERS = "()[]{}/-\\|";

    static final String RESERVED_DOUBLE_PUNCTUATORS = "&!#$%*+,.:;<=>?@^`~";

    static final String MODIFIER_FLAGS = "ims";

    static final int MAX_REPETITION = 0x7FFF_FFFE;

    static final CodePointSet LINE_TERMINATORS = new CodePointSet.Builder().addChar('\n').addChar('\r')
            .addChar('\u2028').addChar('\u2029').build();

    static final CodePointSet WHITESPACE = buildWhitespace();

    static final CodePointSet DIGITS = CodePointSet.of('0', '9');

    static final CodePointSet WORD_CHARS = new CodePointSet.Builder().addRange('a', 'z').addRange('A', 'Z')
            .addRange('0', '9').addChar('_').build();

    static CodePointSet buildWhitespace() {
        final var builder = new CodePointSet.Builder();
        for (final var cp : new int[]{' ', '\t', '\n', 0x0B, '\f', '\r', 0x00a0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f,
                0x3000, 0xfeff}) {
            builder.addChar(cp);
        }
        builder.addRange(0x2000, 0x200a);
        return builder.build();
    }

    static void validateFlags(String flags) {
        for (var i = 0; i < flags.length(); i++) {
            final var flag = flags.charAt(i);
            if (VALID_FLAGS.indexOf(flag) < 0 || flags.indexOf(flag, i + 1) >= 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
        }
        if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
            throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
        }
    }

    static SyntaxErrorException invalid(String detail) {
        return new SyntaxErrorException("Invalid regular expression: " + detail);
    }

    private RegexTables() {
    }
}
