package org.techhouse.simplejs.internal.lexer;

import static org.techhouse.simplejs.internal.lexer.CharClasses.isIdentifierStart;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isRadixDigit;

import java.math.BigInteger;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

public final class NumberLexer {
    public static Lexed lexNumber(String src, int start) {
        final var n = src.length();
        if (src.charAt(start) == '0' && start + 1 < n) {
            final var radix = switch (Character.toLowerCase(src.charAt(start + 1))) {
                case 'x' -> 16;
                case 'o' -> 8;
                case 'b' -> 2;
                default -> 0;
            };
            if (radix != 0) {
                final var end = scanDigits(src, start + 2, n, radix);
                final var digits = src.substring(start + 2, end).replace("_", "");
                if (digits.isEmpty()) {
                    throw new SyntaxErrorException("Missing digits after the numeric literal prefix");
                }
                if (end < n && src.charAt(end) == 'n') {
                    return endOfNumber(new JsBigInt(new BigInteger(digits, radix)), src, end + 1);
                }
                return endOfNumber(new JsNumber((double) Long.parseLong(digits, radix)), src, end);
            }
            if (Character.isDigit(src.charAt(start + 1))) {
                throw new SyntaxErrorException("Octal literals are not allowed in strict mode; use the 0o prefix");
            }
            if (src.charAt(start + 1) == '_') {
                throw new SyntaxErrorException("Numeric separators are not allowed after a leading zero");
            }
        }
        var j = scanDigits(src, start, n, 10);
        final var fractional = j < n && (src.charAt(j) == '.' || src.charAt(j) == 'e' || src.charAt(j) == 'E');
        if (!fractional && j < n && src.charAt(j) == 'n') {
            return endOfNumber(new JsBigInt(new BigInteger(src.substring(start, j).replace("_", ""))), src, j + 1);
        }
        if (j < n && src.charAt(j) == '.') {
            j = scanDigits(src, j + 1, n, 10);
        }
        if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
            var k = j + 1;
            if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) {
                k++;
            }
            if (k < n && Character.isDigit(src.charAt(k))) {
                j = scanDigits(src, k, n, 10);
            }
        }
        return endOfNumber(new JsNumber(Double.parseDouble(src.substring(start, j).replace("_", ""))), src, j);
    }

    public static Lexed endOfNumber(JsBaseElement token, String src, int end) {
        if (end < src.length() && (Character.isDigit(src.charAt(end)) || isIdentifierStart(src, end))) {
            throw new SyntaxErrorException("Identifier or digit directly after a numeric literal");
        }
        return new Lexed(token, end);
    }

    public static int scanDigits(String src, int start, int n, int radix) {
        var j = start;
        while (j < n) {
            final var c = src.charAt(j);
            if (isRadixDigit(c, radix)) {
                j++;
            } else if (c == '_' && j > start && j + 1 < n && isRadixDigit(src.charAt(j + 1), radix)
                    && isRadixDigit(src.charAt(j - 1), radix)) {
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    private NumberLexer() {
    }
}
