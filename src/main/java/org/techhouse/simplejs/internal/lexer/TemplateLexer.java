package org.techhouse.simplejs.internal.lexer;

import static org.techhouse.simplejs.internal.Lexer.scanToken;
import static org.techhouse.simplejs.internal.Lexer.skipComment;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isLineTerminator;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isRadixDigit;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isWhiteSpace;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;

public final class TemplateLexer {
    public static Lexed lexTemplate(String src, int start) {
        final var n = src.length();
        final var quasis = new ArrayList<String>();
        final var rawQuasis = new ArrayList<String>();
        final var expressions = new ArrayList<List<JsBaseElement>>();
        final var builder = new StringBuilder();
        var rawStart = start + 1;
        var i = start + 1;
        var quasiValid = true;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                final var escape = appendTemplateEscape(src, i + 1, builder);
                i = escape.next();
                quasiValid &= escape.valid();
            } else if (c == '`') {
                quasis.add(quasiValid ? builder.toString() : null);
                rawQuasis.add(normalizeLineTerminators(src.substring(rawStart, i)));
                return new Lexed(new JsTemplateString(quasis, rawQuasis, expressions), i + 1);
            } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                quasis.add(quasiValid ? builder.toString() : null);
                rawQuasis.add(normalizeLineTerminators(src.substring(rawStart, i)));
                builder.setLength(0);
                quasiValid = true;
                final var substitution = lexSubstitution(src, i + 2, start);
                expressions.add(substitution.tokens());
                i = substitution.close() + 1;
                rawStart = i;
            } else if (c == '\r') {
                builder.append('\n');
                i += i + 1 < n && src.charAt(i + 1) == '\n' ? 2 : 1;
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedTemplateException(start);
    }

    public static EscapeResult appendTemplateEscape(String src, int i, StringBuilder builder) {
        final var n = src.length();
        final var e = src.charAt(i);
        if (isLineTerminator(e)) {
            return new EscapeResult(e == '\r' && i + 1 < n && src.charAt(i + 1) == '\n' ? i + 2 : i + 1, true);
        }
        switch (e) {
            case 'n' -> builder.append('\n');
            case 't' -> builder.append('\t');
            case 'r' -> builder.append('\r');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'v' -> builder.append((char) 0x0B);
            case '0' -> {
                if (i + 1 < n && Character.isDigit(src.charAt(i + 1))) {
                    return new EscapeResult(i + 2, false);
                }
                builder.append('\0');
            }
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                return new EscapeResult(i + 1, false);
            }
            case 'x' -> {
                return appendTemplateHexEscape(src, i, builder);
            }
            case 'u' -> {
                return appendTemplateUnicodeEscape(src, i, builder);
            }
            default -> builder.append(e);
        }
        return new EscapeResult(i + 1, true);
    }

    public static EscapeResult appendTemplateHexEscape(String src, int i, StringBuilder builder) {
        final var n = src.length();
        var count = 0;
        while (count < 2 && i + 1 + count < n && isRadixDigit(src.charAt(i + 1 + count), 16)) {
            count++;
        }
        if (count == 2) {
            builder.append(
                    (char) (Character.digit(src.charAt(i + 1), 16) * 16 + Character.digit(src.charAt(i + 2), 16)));
            return new EscapeResult(i + 3, true);
        }
        return new EscapeResult(i + 1 + count, false);
    }

    public static EscapeResult appendTemplateUnicodeEscape(String src, int i, StringBuilder builder) {
        final var n = src.length();
        if (i + 1 < n && src.charAt(i + 1) == '{') {
            var count = 0;
            while (i + 2 + count < n && isRadixDigit(src.charAt(i + 2 + count), 16)) {
                count++;
            }
            final var closeIdx = i + 2 + count;
            if (count == 0 || closeIdx >= n || src.charAt(closeIdx) != '}') {
                return new EscapeResult(closeIdx, false);
            }
            long point = 0;
            for (var j = i + 2; j < closeIdx && point <= Character.MAX_CODE_POINT; j++) {
                point = point * 16 + Character.digit(src.charAt(j), 16);
            }
            if (point <= Character.MAX_CODE_POINT) {
                builder.appendCodePoint((int) point);
                return new EscapeResult(closeIdx + 1, true);
            }
            return new EscapeResult(closeIdx + 1, false);
        }
        var count = 0;
        while (count < 4 && i + 1 + count < n && isRadixDigit(src.charAt(i + 1 + count), 16)) {
            count++;
        }
        if (count == 4) {
            builder.append((char) Integer.parseInt(src.substring(i + 1, i + 5), 16));
            return new EscapeResult(i + 5, true);
        }
        return new EscapeResult(i + 1 + count, false);
    }

    public static String normalizeLineTerminators(String raw) {
        if (raw.indexOf('\r') < 0) {
            return raw;
        }
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    public record Substitution(int close, List<JsBaseElement> tokens) {
    }

    public static Substitution lexSubstitution(String src, int from, int templateStart) {
        final var n = src.length();
        final var tokens = new ArrayList<JsBaseElement>();
        final var braces = new BraceContext();
        JsBaseElement last = null;
        var depth = 1;
        var j = from;
        while (j < n) {
            final var c = src.charAt(j);
            if (isWhiteSpace(c)) {
                j++;
                continue;
            }
            if (c == '/' && j + 1 < n && (src.charAt(j + 1) == '/' || src.charAt(j + 1) == '*')) {
                j = skipComment(src, j);
                continue;
            }
            final var lexed = scanToken(src, j, last, braces);
            final var token = lexed.token();
            if (token.getType() == JsType.SEPARATOR) {
                final var separator = ((JsSeparator) token).getValue();
                if (separator == '{') {
                    depth++;
                } else if (separator == '}' && --depth == 0) {
                    tokens.add(JsEOF.getInstance());
                    return new Substitution(j, tokens);
                }
            }
            braces.observe(token, last);
            tokens.add(token);
            last = token;
            j = lexed.next();
        }
        throw new UnterminatedTemplateException(templateStart);
    }

    private TemplateLexer() {
    }
}
