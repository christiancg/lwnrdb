package org.techhouse.ejson.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

public final class Lexer {
    private Lexer() {
    }

    private static final Set<Character> JSON_SYNTAX = Set.of(',', ':', '[', ']', '{', '}');
    private static final Set<Character> JSON_WHITESPACE = Set.of(' ', '\t', '\b', '\n', '\r');
    private static final int FALSE_LEN = "false".length();
    private static final int TRUE_LEN = "true".length();
    private static final int NULL_LEN = "null".length();
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final Set<Character> NUMBER_CHARACTERS = Set.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '-', '.');

    public static List<JsonBaseElement> lex(String input) {
        final var tokens = new ArrayList<JsonBaseElement>();
        for (var i = 0; i < input.length(); i++) {
            final var ss = input.substring(i);
            final var ls = lexString(ss);
            if (ls != null) {
                final var str = ls.value();
                if (JsonCustom.isJsonCustom(str)) {
                    tokens.add(CustomTypeFactory.getCustomTypeInstance(str));
                } else {
                    tokens.add(str);
                }
                i += ls.rawLength() + 1;
                continue;
            }
            final var ld = lexNumber(ss);
            if (ld != null) {
                tokens.add(ld);
                i += ld.getStrLength() - 1;
                continue;
            }
            final var lb = lexBoolean(ss);
            if (lb != null) {
                tokens.add(lb);
                i += (lb.getValue() ? TRUE_LEN : FALSE_LEN) - 1;
                continue;
            }
            final var ln = lexNull(ss);
            if (ln != null) {
                tokens.add(ln);
                i += NULL_LEN - 1;
                continue;
            }
            final var c = input.charAt(i);
            if (JSON_WHITESPACE.contains(c)) {
                continue;
            }
            if (JSON_SYNTAX.contains(c)) {
                tokens.add(JsonSyntaxToken.fromChar(c));
            } else {
                throw new UnexpectedCharacterException(c, i);
            }
        }
        return tokens;
    }

    private record LexedString(JsonString value, int rawLength) {
    }

    private static LexedString lexString(String input) {
        if (input.charAt(0) != '"') {
            return null;
        }
        final var builder = new StringBuilder();
        final var length = input.length();
        var i = 1;
        while (i < length) {
            final var c = input.charAt(i);
            if (c == '"') {
                return new LexedString(new JsonString(builder.toString()), i - 1);
            }
            if (c == '\\' && i + 1 < length) {
                i = appendEscape(builder, input, i + 1);
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new MissingEndOfStringException();
    }

    private static int appendEscape(StringBuilder builder, String input, int position) {
        final var c = input.charAt(position);
        switch (c) {
            case '"' -> builder.append('"');
            case '\\' -> builder.append('\\');
            case '/' -> builder.append('/');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'u' -> {
                return appendUnicodeEscape(builder, input, position + 1);
            }
            // An unrecognised escape is kept verbatim so pre-escaping content degrades instead of failing
            default -> builder.append('\\').append(c);
        }
        return position + 1;
    }

    private static int appendUnicodeEscape(StringBuilder builder, String input, int position) {
        if (position + UNICODE_ESCAPE_DIGITS > input.length()) {
            builder.append("\\u");
            return position;
        }
        var codeUnit = 0;
        for (var i = 0; i < UNICODE_ESCAPE_DIGITS; i++) {
            final var digit = Character.digit(input.charAt(position + i), 16);
            if (digit < 0) {
                builder.append("\\u");
                return position;
            }
            codeUnit = codeUnit * 16 + digit;
        }
        builder.append((char) codeUnit);
        return position + UNICODE_ESCAPE_DIGITS;
    }

    private static JsonNull lexNull(String input) {
        final var length = input.length();
        if (length >= NULL_LEN && input.substring(0, NULL_LEN).equals("null")) {
            return JsonNull.INSTANCE;
        }
        return null;
    }

    private static JsonNumber lexNumber(String input) {
        final var strNumber = new StringBuilder();
        for (var c : input.toCharArray()) {
            if (NUMBER_CHARACTERS.contains(c)) {
                strNumber.append(c);
            } else {
                break;
            }
        }
        if (strNumber.isEmpty()) {
            return null;
        } else {
            return new JsonNumber(strNumber.toString());
        }
    }

    private static JsonBoolean lexBoolean(String input) {
        final var length = input.length();
        if (length >= TRUE_LEN && input.substring(0, TRUE_LEN).equals("true")) {
            return new JsonBoolean(true);
        } else if (length >= FALSE_LEN && input.substring(0, FALSE_LEN).equals("false")) {
            return new JsonBoolean(false);
        }
        return null;
    }
}
