package org.techhouse.simplejs.internal.lexer;

import static org.techhouse.simplejs.internal.lexer.LexerTables.VERTICAL_TILDE;
import static org.techhouse.simplejs.internal.lexer.LexerTables.ZWJ;
import static org.techhouse.simplejs.internal.lexer.LexerTables.ZWNJ;

import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;

public final class CharClasses {
    public static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    public static boolean isWhiteSpace(char c) {
        return c == '\t' || c == '\u000B' || c == '\f' || c == ' ' || c == '\u00A0' || c == '\uFEFF'
                || Character.getType(c) == Character.SPACE_SEPARATOR || isLineTerminator(c);
    }

    public static boolean containsLineTerminator(String src, int from, int to) {
        for (var i = from; i < to; i++) {
            if (isLineTerminator(src.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static int readHex(String src, int from, int count) {
        if (count <= 0 || from + count > src.length()) {
            throw new SyntaxErrorException("Invalid hexadecimal escape sequence");
        }
        var value = 0;
        for (var i = from; i < from + count; i++) {
            final var digit = Character.digit(src.charAt(i), 16);
            if (digit < 0) {
                throw new SyntaxErrorException("Invalid hexadecimal escape sequence");
            }
            value = value * 16 + digit;
            if (value > Character.MAX_CODE_POINT) {
                throw new SyntaxErrorException("Undefined Unicode code-point");
            }
        }
        return value;
    }

    public static boolean isRadixDigit(char c, int radix) {
        return Character.digit(c, radix) >= 0;
    }

    public static int parseCodePoint(String digits, int offset) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException ignored) {
            throw new UnexpectedCharacterException('\\', offset);
        }
    }

    public static boolean isNotValidAt(int point, boolean atStart) {
        return atStart ? !isIdentifierStartPoint(point) : !isIdentifierPartPoint(point);
    }

    public static boolean isIdentifierStartPoint(int point) {
        return (Character.isUnicodeIdentifierStart(point) && point != VERTICAL_TILDE) || point == '$' || point == '_';
    }

    public static boolean isIdentifierPartPoint(int point) {
        if (point == '$' || point == ZWNJ || point == ZWJ) {
            return true;
        }
        return Character.isUnicodeIdentifierPart(point) && !Character.isIdentifierIgnorable(point)
                && point != VERTICAL_TILDE;
    }

    public static boolean isIdentifierStart(String src, int pos) {
        final var c = src.charAt(pos);
        if (c == '\\') {
            return pos + 1 < src.length() && src.charAt(pos + 1) == 'u';
        }
        return isIdentifierStartPoint(src.codePointAt(pos));
    }

    private CharClasses() {
    }
}
