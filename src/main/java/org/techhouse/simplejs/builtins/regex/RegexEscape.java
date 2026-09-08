package org.techhouse.simplejs.builtins.regex;

import static org.techhouse.simplejs.builtins.RegexBuiltins.BYTE_ORDER_MARK;
import static org.techhouse.simplejs.builtins.RegexBuiltins.LINE_SEPARATOR;
import static org.techhouse.simplejs.builtins.RegexBuiltins.NO_BREAK_SPACE;
import static org.techhouse.simplejs.builtins.RegexBuiltins.OTHER_PUNCTUATORS;
import static org.techhouse.simplejs.builtins.RegexBuiltins.PARAGRAPH_SEPARATOR;
import static org.techhouse.simplejs.builtins.RegexBuiltins.VERTICAL_TAB;
import static org.techhouse.simplejs.internal.regex.RegexTables.SYNTAX_CHARACTERS;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsValue;

public final class RegexEscape {
    public static String escape(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsString first)) {
            throw new TypeErrorException("RegExp.escape argument must be a string");
        }
        final var value = first.getValue();
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (i == 0 && isAlphanumeric(ch)) {
                appendHex(result, ch);
            } else if (SYNTAX_CHARACTERS.indexOf(ch) >= 0 || ch == '/') {
                result.append('\\').append(ch);
            } else {
                appendNamedOrLiteral(result, value, i);
            }
        }
        return result.toString();
    }

    public static void appendNamedOrLiteral(StringBuilder result, String value, int index) {
        final var ch = value.charAt(index);
        switch (ch) {
            case '\t' -> result.append("\\t");
            case '\n' -> result.append("\\n");
            case VERTICAL_TAB -> result.append("\\v");
            case '\f' -> result.append("\\f");
            case '\r' -> result.append("\\r");
            default -> {
                if (OTHER_PUNCTUATORS.indexOf(ch) >= 0 || isWhiteSpace(ch) || isLineTerminator(ch)
                        || isLoneSurrogate(value, index)) {
                    appendHex(result, ch);
                } else {
                    result.append(ch);
                }
            }
        }
    }

    public static boolean isAlphanumeric(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    public static boolean isLineTerminator(char ch) {
        return ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR;
    }

    public static boolean isWhiteSpace(char ch) {
        return ch == ' ' || ch == NO_BREAK_SPACE || ch == BYTE_ORDER_MARK
                || Character.getType(ch) == Character.SPACE_SEPARATOR;
    }

    public static boolean isLoneSurrogate(String value, int index) {
        final var ch = value.charAt(index);
        if (Character.isHighSurrogate(ch)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        return Character.isLowSurrogate(ch) && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
    }

    public static void appendHex(StringBuilder result, char ch) {
        if (ch <= 0xFF) {
            result.append("\\x").append(String.format("%02x", (int) ch));
        } else {
            result.append("\\u").append(String.format("%04x", (int) ch));
        }
    }

    private RegexEscape() {
    }
}
