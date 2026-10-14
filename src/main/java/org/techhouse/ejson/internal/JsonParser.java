package org.techhouse.ejson.internal;

import java.util.NoSuchElementException;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBaseElement.JsonType;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

public final class JsonParser {
    private enum Token {
        END, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET, COMMA, COLON, STRING, VALUE
    }

    private static final String TRUE_LITERAL = "true";
    private static final String FALSE_LITERAL = "false";
    private static final String NULL_LITERAL = "null";
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final int HEX_RADIX = 16;

    private final String input;
    private final int length;
    private final int maxTokens;
    private final KeyTable keyTable;
    private int position;
    private int tokens;
    private long customComponents;
    private JsonBaseElement value;
    private int textStart;
    private int textEnd;
    private String decoded;

    private JsonParser(String input, int maxTokens, KeyTable keyTable) {
        this.input = input;
        this.length = input.length();
        this.maxTokens = maxTokens;
        this.keyTable = keyTable;
    }

    public static JsonBaseElement parse(String input, int maxDepth, int maxTokens) {
        return parse(input, maxDepth, maxTokens, null);
    }

    public static JsonBaseElement parse(String input, int maxDepth, int maxTokens, KeyTable keyTable) {
        return new JsonParser(input, maxTokens, keyTable).parseRoot(maxDepth);
    }

    private JsonBaseElement parseRoot(int maxDepth) {
        final var first = next();
        if (first == Token.END) {
            throw new MalformedJsonException("Empty JSON array");
        }
        if (first != Token.LEFT_BRACE && first != Token.LEFT_BRACKET) {
            throw fail("Json must start with either a left bracket or a left brace");
        }
        final var root = parseValue(first, maxDepth);
        if (next() != Token.END) {
            throw fail("Unexpected content after the root value");
        }
        return root;
    }

    private JsonBaseElement parseValue(Token token, int remainingDepth) {
        return switch (token) {
            case END -> throw fail("Empty JSON array");
            case LEFT_BRACKET -> parseArray(nestedDepth(remainingDepth));
            case LEFT_BRACE -> parseObject(nestedDepth(remainingDepth));
            case STRING -> new JsonString(text());
            case VALUE -> value;
            default -> throw fail("Expected a value, got " + JsonType.SYNTAX);
        };
    }

    private int nestedDepth(int remainingDepth) {
        if (remainingDepth <= 0) {
            throw fail("JSON nesting is deeper than the allowed limit");
        }
        return remainingDepth - 1;
    }

    private JsonObject parseObject(int remainingDepth) {
        final var object = new JsonObject();
        var token = nextPresent();
        if (token == Token.RIGHT_BRACE) {
            return object;
        }
        for (;;) {
            final var key = keyOf(token);
            token = nextPresent();
            if (token != Token.COLON) {
                throw fail("Expected colon after key in object, got: " + typeOf(token));
            }
            object.add(key, parseValue(next(), remainingDepth));
            token = nextPresent();
            if (token == Token.RIGHT_BRACE) {
                return object;
            }
            if (token != Token.COMMA) {
                throw fail("Expected comma after pair in object, got: " + typeOf(token));
            }
            token = nextPresent();
        }
    }

    private JsonArray parseArray(int remainingDepth) {
        final var array = new JsonArray();
        var token = nextPresent();
        if (token == Token.RIGHT_BRACKET) {
            return array;
        }
        for (;;) {
            array.add(parseValue(token, remainingDepth));
            token = nextPresent();
            if (token == Token.RIGHT_BRACKET) {
                return array;
            }
            if (token != Token.COMMA) {
                throw fail("Expected comma after object in array");
            }
            token = next();
        }
    }

    private String keyOf(Token token) {
        if (token == Token.STRING) {
            if (decoded != null) {
                return canonicalKey(decoded, 0, decoded.length());
            }
            return canonicalKey(input, textStart, textEnd);
        }
        if (token == Token.VALUE && value.isJsonString()) {
            return value.asJsonString().getValue();
        }
        throw fail("Expected string key, got " + typeOf(token));
    }

    private String canonicalKey(String source, int start, int end) {
        return keyTable == null ? KeyTable.canonical(source, start, end) : keyTable.lookup(source, start, end);
    }

    private JsonType typeOf(Token token) {
        return switch (token) {
            case STRING -> JsonType.STRING;
            case VALUE -> value.getJsonType();
            default -> JsonType.SYNTAX;
        };
    }

    private String text() {
        return decoded != null ? decoded : input.substring(textStart, textEnd);
    }

    private MalformedJsonException fail(String message) {
        var token = next();
        while (token != Token.END) {
            token = next();
        }
        return new MalformedJsonException(message);
    }

    private Token nextPresent() {
        final var token = next();
        if (token == Token.END) {
            throw new NoSuchElementException();
        }
        return token;
    }

    private Token next() {
        while (position < length) {
            final var c = input.charAt(position);
            switch (c) {
                case '"' -> {
                    return lexString();
                }
                case '{' -> {
                    return syntax(Token.LEFT_BRACE);
                }
                case '}' -> {
                    return syntax(Token.RIGHT_BRACE);
                }
                case '[' -> {
                    return syntax(Token.LEFT_BRACKET);
                }
                case ']' -> {
                    return syntax(Token.RIGHT_BRACKET);
                }
                case ',' -> {
                    return syntax(Token.COMMA);
                }
                case ':' -> {
                    return syntax(Token.COLON);
                }
                case ' ', '\t', '\b', '\n', '\r' -> {
                    position++;
                    checkBudget();
                }
                default -> {
                    return lexOther(c);
                }
            }
        }
        return Token.END;
    }

    private Token syntax(Token token) {
        position++;
        return counted(token);
    }

    private Token lexOther(char c) {
        if (isNumberCharacter(c)) {
            return lexNumber();
        }
        if (input.startsWith(TRUE_LITERAL, position)) {
            return literal(JsonBoolean.TRUE, TRUE_LITERAL.length());
        }
        if (input.startsWith(FALSE_LITERAL, position)) {
            return literal(JsonBoolean.FALSE, FALSE_LITERAL.length());
        }
        if (input.startsWith(NULL_LITERAL, position)) {
            return literal(JsonNull.INSTANCE, NULL_LITERAL.length());
        }
        throw new UnexpectedCharacterException(c, position);
    }

    private Token literal(JsonBaseElement literal, int literalLength) {
        position += literalLength;
        value = literal;
        return counted(Token.VALUE);
    }

    private Token counted(Token token) {
        tokens++;
        checkBudget();
        return token;
    }

    private void checkBudget() {
        if (tokens + customComponents > maxTokens) {
            throw new MalformedJsonException("JSON has more tokens than the allowed limit");
        }
    }

    private static boolean isNumberCharacter(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private Token lexNumber() {
        final var start = position;
        var index = start;
        while (index < length && isNumberCharacter(input.charAt(index))) {
            index++;
        }
        final var end = exponentEnd(index);
        value = new JsonNumber(input.substring(start, end));
        position = end;
        return counted(Token.VALUE);
    }

    private int exponentEnd(int start) {
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

    private Token lexString() {
        final var start = position + 1;
        var index = start;
        while (index < length) {
            final var c = input.charAt(index);
            if (c == '"') {
                textStart = start;
                textEnd = index;
                decoded = null;
                position = index + 1;
                return finishString(index > start && input.charAt(start) == '#');
            }
            if (c == '\\') {
                return lexEscapedString(start, index);
            }
            index++;
        }
        throw new MissingEndOfStringException();
    }

    private Token lexEscapedString(int start, int firstEscape) {
        final var builder = new StringBuilder(firstEscape - start + 16);
        builder.append(input, start, firstEscape);
        var index = firstEscape;
        while (index < length) {
            final var c = input.charAt(index);
            if (c == '"') {
                decoded = builder.toString();
                position = index + 1;
                return finishString(!decoded.isEmpty() && decoded.charAt(0) == '#');
            }
            if (c == '\\' && index + 1 < length) {
                index = appendEscape(builder, index + 1);
            } else {
                builder.append(c);
                index++;
            }
        }
        throw new MissingEndOfStringException();
    }

    private Token finishString(boolean mayBeCustom) {
        if (mayBeCustom) {
            final var string = new JsonString(text());
            if (JsonCustom.isJsonCustom(string)) {
                if (maxTokens != Integer.MAX_VALUE) {
                    chargeCustom(string.getValue());
                    checkBudget();
                }
                value = CustomTypeFactory.getCustomTypeInstance(string);
                return counted(Token.VALUE);
            }
        }
        return counted(Token.STRING);
    }

    private void chargeCustom(String literal) {
        final var components = literal.chars().filter(c -> c == ',').count() + 1;
        if (components > Configuration.getInstance().intValue(ConfigKey.MAX_VECTOR_DIMENSIONS)) {
            throw new MalformedJsonException("A custom value has more components than the allowed limit");
        }
        customComponents += components;
    }

    private int appendEscape(StringBuilder builder, int escaped) {
        final var c = input.charAt(escaped);
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
                return appendUnicodeEscape(builder, escaped + 1);
            }
            default -> builder.append('\\').append(c);
        }
        return escaped + 1;
    }

    private int appendUnicodeEscape(StringBuilder builder, int digits) {
        if (digits + UNICODE_ESCAPE_DIGITS > length) {
            builder.append("\\u");
            return digits;
        }
        var codeUnit = 0;
        for (var offset = 0; offset < UNICODE_ESCAPE_DIGITS; offset++) {
            final var digit = Character.digit(input.charAt(digits + offset), HEX_RADIX);
            if (digit < 0) {
                builder.append("\\u");
                return digits;
            }
            codeUnit = codeUnit * HEX_RADIX + digit;
        }
        builder.append((char) codeUnit);
        return digits + UNICODE_ESCAPE_DIGITS;
    }
}
