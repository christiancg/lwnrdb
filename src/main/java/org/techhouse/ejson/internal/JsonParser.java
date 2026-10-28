package org.techhouse.ejson.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private static final int MAX_UTF8_CHAR_BYTES = 4;
    private static final int MAX_EXACT_DIGITS = 15;
    private static final int BYTE_MASK = 0xFF;
    private static final int INITIAL_MEMBER_STACK = 16;

    private final String input;
    private final byte[] bytes;
    private final int origin;
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
    private String[] memberKeys = new String[0];
    private JsonBaseElement[] memberValues = new JsonBaseElement[0];
    private int memberCount;

    private JsonParser(String input, byte[] bytes, int start, int end, int maxTokens, KeyTable keyTable) {
        this.input = input;
        this.bytes = bytes;
        this.origin = start;
        this.length = end;
        this.position = start;
        this.maxTokens = maxTokens;
        this.keyTable = keyTable;
    }

    public static JsonBaseElement parse(String input, int maxDepth, int maxTokens) {
        return parse(input, maxDepth, maxTokens, null);
    }

    public static JsonBaseElement parse(String input, int maxDepth, int maxTokens, KeyTable keyTable) {
        return new JsonParser(input, null, 0, input.length(), maxTokens, keyTable).parseRoot(maxDepth);
    }

    public static JsonBaseElement parse(byte[] utf8, int start, int end, int maxDepth, int maxTokens) {
        return parse(utf8, start, end, maxDepth, maxTokens, null);
    }

    public static JsonBaseElement parse(byte[] utf8, int start, int end, int maxDepth, int maxTokens,
            KeyTable keyTable) {
        return new JsonParser(null, utf8, start, end, maxTokens, keyTable).parseRoot(maxDepth);
    }

    private int charAt(int index) {
        return input != null ? input.charAt(index) : bytes[index] & BYTE_MASK;
    }

    private String slice(int start, int end) {
        return input != null
                ? input.substring(start, end)
                : new String(bytes, start, end - start, StandardCharsets.UTF_8);
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
        var token = nextPresent();
        if (token == Token.RIGHT_BRACE) {
            return new JsonObject();
        }
        final var base = memberCount;
        for (;;) {
            final var key = keyOf(token);
            token = nextPresent();
            if (token != Token.COLON) {
                throw fail("Expected colon after key in object, got: " + typeOf(token));
            }
            pushMember(key, parseValue(next(), remainingDepth));
            token = nextPresent();
            if (token == Token.RIGHT_BRACE) {
                return popObject(base);
            }
            if (token != Token.COMMA) {
                throw fail("Expected comma after pair in object, got: " + typeOf(token));
            }
            token = nextPresent();
        }
    }

    private void pushMember(String key, JsonBaseElement member) {
        if (memberCount == memberKeys.length) {
            final var capacity = Math.max(INITIAL_MEMBER_STACK, memberKeys.length * 2);
            memberKeys = Arrays.copyOf(memberKeys, capacity);
            memberValues = Arrays.copyOf(memberValues, capacity);
        }
        memberKeys[memberCount] = key;
        memberValues[memberCount] = member;
        memberCount++;
    }

    private JsonObject popObject(int base) {
        final var shape = keyTable == null ? ShapeTable.shared(memberKeys, base, memberCount) : null;
        final var object = shape == null
                ? memberwise(base)
                : JsonObject.withShape(shape, Arrays.copyOfRange(memberValues, base, memberCount));
        Arrays.fill(memberKeys, base, memberCount, null);
        Arrays.fill(memberValues, base, memberCount, null);
        memberCount = base;
        return object;
    }

    private JsonObject memberwise(int base) {
        final var object = new JsonObject(memberCount - base);
        for (var index = base; index < memberCount; index++) {
            object.add(memberKeys[index], memberValues[index]);
        }
        return object;
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
            if (input == null) {
                return keyTable == null
                        ? KeyTable.canonical(bytes, textStart, textEnd)
                        : keyTable.lookup(bytes, textStart, textEnd);
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
        return decoded != null ? decoded : slice(textStart, textEnd);
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
            final var c = charAt(position);
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

    private Token lexOther(int c) {
        if (isNumberCharacter(c)) {
            return lexNumber();
        }
        if (startsWithAt(TRUE_LITERAL)) {
            return literal(JsonBoolean.TRUE, TRUE_LITERAL.length());
        }
        if (startsWithAt(FALSE_LITERAL)) {
            return literal(JsonBoolean.FALSE, FALSE_LITERAL.length());
        }
        if (startsWithAt(NULL_LITERAL)) {
            return literal(JsonNull.INSTANCE, NULL_LITERAL.length());
        }
        throw unexpectedCharacter();
    }

    private boolean startsWithAt(String literal) {
        if (input != null) {
            return input.startsWith(literal, position);
        }
        if (position + literal.length() > length) {
            return false;
        }
        for (var offset = 0; offset < literal.length(); offset++) {
            if (bytes[position + offset] != literal.charAt(offset)) {
                return false;
            }
        }
        return true;
    }

    private UnexpectedCharacterException unexpectedCharacter() {
        if (input != null) {
            return new UnexpectedCharacterException(input.charAt(position), position);
        }
        final var charIndex = new String(bytes, origin, position - origin, StandardCharsets.UTF_8).length();
        final var window = Math.min(length - position, MAX_UTF8_CHAR_BYTES);
        final var character = new String(bytes, position, window, StandardCharsets.UTF_8).charAt(0);
        return new UnexpectedCharacterException(character, charIndex);
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

    private static boolean isNumberCharacter(int c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private Token lexNumber() {
        final var start = position;
        var index = start;
        while (index < length && isNumberCharacter(charAt(index))) {
            index++;
        }
        final var end = exponentEnd(index);
        value = input != null ? new JsonNumber(input.substring(start, end)) : numberFromBytes(start, end);
        position = end;
        return counted(Token.VALUE);
    }

    private JsonNumber numberFromBytes(int start, int end) {
        final var negative = bytes[start] == '-';
        final var digitsStart = negative ? start + 1 : start;
        final var digits = end - digitsStart;
        if (digits < 1 || digits > MAX_EXACT_DIGITS) {
            return new JsonNumber(slice(start, end));
        }
        var magnitude = 0L;
        for (var index = digitsStart; index < end; index++) {
            final var digit = bytes[index] - '0';
            if (digit < 0 || digit > 9) {
                return new JsonNumber(slice(start, end));
            }
            magnitude = magnitude * 10 + digit;
        }
        return new JsonNumber(negative && magnitude != 0 ? -(double) magnitude : magnitude);
    }

    private int exponentEnd(int start) {
        var index = start;
        if (index >= length || (charAt(index) != 'e' && charAt(index) != 'E')) {
            return start;
        }
        index++;
        if (index < length && (charAt(index) == '+' || charAt(index) == '-')) {
            index++;
        }
        var width = digitWidthAt(index);
        if (width == 0) {
            return start;
        }
        while (width > 0) {
            index += width;
            width = digitWidthAt(index);
        }
        return index;
    }

    private int digitWidthAt(int index) {
        if (index >= length) {
            return 0;
        }
        final var c = charAt(index);
        if (input != null || c < 0x80) {
            return Character.isDigit((char) c) ? 1 : 0;
        }
        final var window = new String(bytes, index, Math.min(length - index, MAX_UTF8_CHAR_BYTES),
                StandardCharsets.UTF_8);
        final var decodedChar = window.charAt(0);
        return Character.isDigit(decodedChar) ? String.valueOf(decodedChar).getBytes(StandardCharsets.UTF_8).length : 0;
    }

    private Token lexString() {
        final var start = position + 1;
        var index = start;
        while (index < length) {
            final var c = charAt(index);
            if (c == '"') {
                textStart = start;
                textEnd = index;
                decoded = null;
                position = index + 1;
                return finishString(index > start && charAt(start) == '#');
            }
            if (c == '\\') {
                return lexEscapedString(start, index);
            }
            index++;
        }
        throw new MissingEndOfStringException();
    }

    private Token lexEscapedString(int start, int firstEscape) {
        final var end = endOfEscapedString(firstEscape);
        final var raw = slice(start, end);
        decoded = JsonEscapes.unescape(raw, raw.indexOf('\\'));
        position = end + 1;
        return finishString(!decoded.isEmpty() && decoded.charAt(0) == '#');
    }

    private int endOfEscapedString(int firstEscape) {
        var index = firstEscape;
        while (index < length) {
            final var c = charAt(index);
            if (c == '"') {
                return index;
            }
            index += c == '\\' && index + 1 < length ? 2 : 1;
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
        if (components > CustomLimits.configuration.intValue(ConfigKey.MAX_VECTOR_DIMENSIONS)) {
            throw new MalformedJsonException("A custom value has more components than the allowed limit");
        }
        customComponents += components;
    }

    private static final class CustomLimits {
        private static final Configuration configuration = Configuration.getInstance();
    }
}
