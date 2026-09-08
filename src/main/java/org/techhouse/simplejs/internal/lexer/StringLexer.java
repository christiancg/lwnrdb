package org.techhouse.simplejs.internal.lexer;

import static org.techhouse.simplejs.internal.lexer.CharClasses.isLineTerminator;
import static org.techhouse.simplejs.internal.lexer.CharClasses.readHex;

import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;

public final class StringLexer {

    public static Lexed lexString(String src, int start) {
        final var quote = src.charAt(start);
        final var n = src.length();
        final var builder = new StringBuilder();
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                i = appendEscape(src, i + 1, builder);
            } else if (c == quote) {
                return new Lexed(new JsString(builder.toString()), i + 1);
            } else if (c == '\n' || c == '\r') {
                break;
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedStringException(start);
    }

    public static int appendEscape(String src, int i, StringBuilder builder) {
        final var e = src.charAt(i);
        if (isLineTerminator(e)) {
            return e == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        }
        switch (e) {
            case 'n' -> builder.append('\n');
            case 't' -> builder.append('\t');
            case 'r' -> builder.append('\r');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'v' -> builder.append('\u000B');
            case '0' -> {
                if (i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
                    throw new SyntaxErrorException("Octal escape sequences are not allowed in strict mode");
                }
                builder.append('\0');
            }
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                throw new SyntaxErrorException("Octal escape sequences are not allowed in strict mode");
            case 'x' -> {
                builder.append((char) readHex(src, i + 1, 2));
                return i + 3;
            }
            case 'u' -> {
                return appendUnicodeEscape(src, i, builder);
            }
            default -> builder.append(e);
        }
        return i + 1;
    }

    public static int appendUnicodeEscape(String src, int i, StringBuilder builder) {
        final var n = src.length();
        if (i + 1 < n && src.charAt(i + 1) == '{') {
            final var end = src.indexOf('}', i + 2);
            if (end < 0) {
                throw new SyntaxErrorException("Invalid Unicode escape sequence");
            }
            final var point = readHex(src, i + 2, end - i - 2);
            if (point > Character.MAX_CODE_POINT) {
                throw new SyntaxErrorException("Undefined Unicode code-point");
            }
            builder.appendCodePoint(point);
            return end + 1;
        }
        builder.append((char) readHex(src, i + 1, 4));
        return i + 5;
    }

    private StringLexer() {
    }
}
