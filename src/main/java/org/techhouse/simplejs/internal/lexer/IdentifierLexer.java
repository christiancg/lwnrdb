package org.techhouse.simplejs.internal.lexer;

import static org.techhouse.simplejs.internal.lexer.CharClasses.isNotValidAt;
import static org.techhouse.simplejs.internal.lexer.CharClasses.parseCodePoint;
import static org.techhouse.simplejs.internal.lexer.LexerTables.ESCAPE_RESERVED;
import static org.techhouse.simplejs.internal.lexer.LexerTables.JS_KEYWORD;

import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNull;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsUndefined;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;

public final class IdentifierLexer {
    public record IdentifierScan(String name, int next, boolean escaped) {
    }

    public record EscapePoint(int point, int next) {
    }

    public static Lexed lexWord(String src, int start) {
        final var scan = scanIdentifier(src, start);
        final var word = scan.name();
        if (scan.escaped() && (JS_KEYWORD.contains(word) || ESCAPE_RESERVED.contains(word))) {
            return new Lexed(new JsIdentifier(word, true), scan.next());
        }
        final JsBaseElement token = switch (word) {
            case "true" -> new JsBoolean(true);
            case "false" -> new JsBoolean(false);
            case "null" -> JsNull.getInstance();
            case "undefined" -> JsUndefined.getInstance();
            default -> JS_KEYWORD.contains(word) ? new JsKeyword(word) : new JsIdentifier(word, scan.escaped());
        };
        return new Lexed(token, scan.next());
    }

    public static Lexed lexPrivateIdentifier(String src, int start) {
        final var scan = scanIdentifier(src, start + 1);
        return new Lexed(new JsPrivateIdentifier(scan.name()), scan.next());
    }

    public static IdentifierScan scanIdentifier(String src, int start) {
        final var n = src.length();
        final var builder = new StringBuilder();
        var i = start;
        var escaped = false;
        while (i < n) {
            if (src.charAt(i) == '\\') {
                final var decoded = decodeIdentifierEscape(src, i);
                if (isNotValidAt(decoded.point(), builder.isEmpty())) {
                    throw new UnexpectedCharacterException('\\', i);
                }
                builder.appendCodePoint(decoded.point());
                i = decoded.next();
                escaped = true;
                continue;
            }
            final var point = src.codePointAt(i);
            if (isNotValidAt(point, builder.isEmpty())) {
                break;
            }
            builder.appendCodePoint(point);
            i += Character.charCount(point);
        }
        return new IdentifierScan(builder.toString(), i, escaped);
    }

    public static EscapePoint decodeIdentifierEscape(String src, int i) {
        final var n = src.length();
        if (i + 1 >= n || src.charAt(i + 1) != 'u') {
            throw new UnexpectedCharacterException('\\', i);
        }
        if (i + 2 < n && src.charAt(i + 2) == '{') {
            final var end = src.indexOf('}', i + 3);
            if (end < 0) {
                throw new UnexpectedCharacterException('\\', i);
            }
            return new EscapePoint(parseCodePoint(src.substring(i + 3, end), i), end + 1);
        }
        if (i + 6 > n) {
            throw new UnexpectedCharacterException('\\', i);
        }
        return new EscapePoint(parseCodePoint(src.substring(i + 2, i + 6), i), i + 6);
    }

    private IdentifierLexer() {
    }
}
