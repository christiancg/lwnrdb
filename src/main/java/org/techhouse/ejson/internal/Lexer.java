package org.techhouse.ejson.internal;

import java.util.ArrayList;
import java.util.List;
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

    private static final String TRUE_LITERAL = "true";
    private static final String FALSE_LITERAL = "false";
    private static final String NULL_LITERAL = "null";
    private static final int FALSE_LEN = FALSE_LITERAL.length();
    private static final int TRUE_LEN = TRUE_LITERAL.length();
    private static final int NULL_LEN = NULL_LITERAL.length();
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 8;

    public static List<JsonBaseElement> lex(String input) {
        final var length = input.length();
        final var tokens = new ArrayList<JsonBaseElement>(length / ESTIMATED_CHARS_PER_TOKEN + 16);
        var i = 0;
        while (i < length) {
            final var c = input.charAt(i);
            if (c == '"') {
                i = lexString(input, i, tokens);
            } else if (isNumberCharacter(c)) {
                i = lexNumber(input, i, tokens);
            } else if (input.regionMatches(i, TRUE_LITERAL, 0, TRUE_LEN)) {
                tokens.add(new JsonBoolean(true));
                i += TRUE_LEN;
            } else if (input.regionMatches(i, FALSE_LITERAL, 0, FALSE_LEN)) {
                tokens.add(new JsonBoolean(false));
                i += FALSE_LEN;
            } else if (input.regionMatches(i, NULL_LITERAL, 0, NULL_LEN)) {
                tokens.add(JsonNull.INSTANCE);
                i += NULL_LEN;
            } else if (isJsonWhitespace(c)) {
                i++;
            } else if (isJsonSyntax(c)) {
                tokens.add(JsonSyntaxToken.fromChar(c));
                i++;
            } else {
                throw new UnexpectedCharacterException(c, i);
            }
        }
        return tokens;
    }

    private static boolean isNumberCharacter(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\b' || c == '\n' || c == '\r';
    }

    private static boolean isJsonSyntax(char c) {
        return c == ',' || c == ':' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    private static int lexString(String input, int start, List<JsonBaseElement> tokens) {
        final var builder = new StringBuilder();
        final var length = input.length();
        var i = start + 1;
        while (i < length) {
            final var c = input.charAt(i);
            if (c == '"') {
                final var str = new JsonString(builder.toString());
                tokens.add(JsonCustom.isJsonCustom(str) ? CustomTypeFactory.getCustomTypeInstance(str) : str);
                return i + 1;
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

    private static int lexNumber(String input, int start, List<JsonBaseElement> tokens) {
        final var length = input.length();
        var index = start;
        while (index < length && isNumberCharacter(input.charAt(index))) {
            index++;
        }
        final var end = exponentEnd(input, index);
        tokens.add(new JsonNumber(input.substring(start, end)));
        return end;
    }

    private static int exponentEnd(String input, int start) {
        final var length = input.length();
        var index = start;
        if (index >= length || (input.charAt(index) != 'e' && input.charAt(index) != 'E')) {
            return start;
        }
        index++;
        if (index < length && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
            index++;
        }
        if (index >= length || !Character.isDigit(input.charAt(index))) {
            return start;
        }
        while (index < length && Character.isDigit(input.charAt(index))) {
            index++;
        }
        return index;
    }
}
