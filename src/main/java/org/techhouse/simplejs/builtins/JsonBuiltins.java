package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.json.JsonStringify.stringify;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsonBuiltins {
    public static final EJson EJSON = new EJson();
    public static final int MAX_INDENT = 10;

    public record Replacer(JsValue function, List<String> allowList) {
    }

    private JsonBuiltins() {
    }

    public static JsObject create(InterpreterOps ops, Invoker invoker) {
        return create(ops, invoker, null);
    }

    public static JsObject create(InterpreterOps ops, Invoker invoker, JsObject objectProto) {
        final var json = new JsObject();
        Intrinsics.defineHidden(json, "parse",
                new JsNativeFunction("parse", (_, args) -> parse(args, ops, invoker, objectProto)));
        Intrinsics.defineHidden(json, "stringify",
                new JsNativeFunction("stringify", (_, args) -> stringify(args, ops, invoker, objectProto)));
        Intrinsics.defineNamespaceTag(json, "JSON");
        return json;
    }

    private static JsValue parse(List<JsValue> args, InterpreterOps ops, Invoker invoker, JsObject objectProto) {
        final var text = args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
        InterpreterOps.chargeChars(ops, text.length());
        final var parsed = new JsonTextParser(text, objectProto).parseText();
        final var reviver = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!isCallable(reviver)) {
            return parsed;
        }
        final var holder = newHolder(objectProto);
        holder.defineValue("", parsed);
        return internalize(holder, "", reviver, ops, invoker);
    }

    private static JsValue internalize(JsValue holder, String key, JsValue reviver, InterpreterOps ops,
            Invoker invoker) {
        final var value = ops.getMember(holder, new JsString(key));
        if (InterpreterUtils.isObjectLike(value) && !isCallable(value)) {
            if (isArray(value)) {
                final var length = (long) JsCoercion.toNumber(ops.getMember(value, new JsString("length")), ops);
                for (var i = 0L; i < length; i++) {
                    reviveChild(value, Long.toString(i), reviver, ops, invoker);
                }
            } else {
                for (final var child : ops.ownKeys(value)) {
                    if (child instanceof JsString name) {
                        reviveChild(value, name.getValue(), reviver, ops, invoker);
                    }
                }
            }
        }
        return invoker.call(reviver, holder, List.of(new JsString(key), value));
    }

    public static boolean isArray(JsValue value) {
        return value instanceof JsArray || (value instanceof JsProxy proxy && isArray(proxy.getTarget()));
    }

    private static void reviveChild(JsValue holder, String key, JsValue reviver, InterpreterOps ops, Invoker invoker) {
        final var revived = internalize(holder, key, reviver, ops, invoker);
        final var name = new JsString(key);
        if (revived instanceof JsUndefined) {
            ops.deleteMember(holder, name);
        } else {
            if (isRedefinable(holder, name, ops)) {
                ops.defineProperty(holder, name, dataDescriptor(revived));
            }
        }
    }

    private static boolean isRedefinable(JsValue holder, JsString name, InterpreterOps ops) {
        return !(ops.getOwnPropertyDescriptor(holder, name) instanceof JsObject descriptor)
                || !descriptor.has("configurable") || JsCoercion.toBoolean(descriptor.get("configurable"));
    }

    private static JsObject dataDescriptor(JsValue value) {
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(true));
        descriptor.set("configurable", JsBoolean.of(true));
        return descriptor;
    }

    private static final class JsonTextParser {
        private final String source;
        private final JsObject objectProto;
        private int pos;

        private JsonTextParser(String source, JsObject objectProto) {
            this.source = source;
            this.objectProto = objectProto;
        }

        private JsValue parseText() {
            skipWhitespace();
            final var value = parseValue();
            skipWhitespace();
            if (pos != source.length()) {
                throw fail("Unexpected non-whitespace character after JSON");
            }
            return value;
        }

        private JsValue parseValue() {
            if (pos >= source.length()) {
                throw fail("Unexpected end of JSON input");
            }
            return switch (source.charAt(pos)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsString(parseString());
                case 't' -> literal("true", JsBoolean.of(true));
                case 'f' -> literal("false", JsBoolean.of(false));
                case 'n' -> literal("null", JsNull.getInstance());
                default -> parseNumber();
            };
        }

        private JsValue literal(String text, JsValue value) {
            if (!source.startsWith(text, pos)) {
                throw fail("Unexpected token in JSON");
            }
            pos += text.length();
            return value;
        }

        private JsValue parseObject() {
            pos++;
            final var object = newHolder(objectProto);
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            var more = true;
            while (more) {
                skipWhitespace();
                if (peek() != '"') {
                    throw fail("Expected a property name in JSON");
                }
                final var key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.defineValue(key, parseValue());
                skipWhitespace();
                more = peek() == ',';
                if (more) {
                    pos++;
                }
            }
            expect('}');
            return object;
        }

        private JsValue parseArray() {
            pos++;
            final var array = new JsArray();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return array;
            }
            var more = true;
            while (more) {
                skipWhitespace();
                array.push(parseValue());
                skipWhitespace();
                more = peek() == ',';
                if (more) {
                    pos++;
                }
            }
            expect(']');
            return array;
        }

        private String parseString() {
            pos++;
            final var sb = new StringBuilder();
            while (true) {
                if (pos >= source.length()) {
                    throw fail("Unterminated string in JSON");
                }
                final var c = source.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw fail("Bad control character in JSON string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                sb.append(parseEscape());
            }
        }

        private char parseEscape() {
            if (pos >= source.length()) {
                throw fail("Unterminated string in JSON");
            }
            final var escape = source.charAt(pos++);
            return switch (escape) {
                case '"', '\\', '/' -> escape;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw fail("Bad escaped character in JSON string");
            };
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > source.length()) {
                throw fail("Bad Unicode escape in JSON string");
            }
            final var digits = source.substring(pos, pos + 4);
            for (var i = 0; i < 4; i++) {
                if (Character.digit(digits.charAt(i), 16) < 0) {
                    throw fail("Bad Unicode escape in JSON string");
                }
            }
            pos += 4;
            return (char) Integer.parseInt(digits, 16);
        }

        private JsValue parseNumber() {
            final var start = pos;
            if (peek() == '-') {
                pos++;
            }
            digits(true);
            if (peek() == '.') {
                pos++;
                digits(false);
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                digits(false);
            }
            return new JsNumber(Double.parseDouble(source.substring(start, pos)));
        }

        private void digits(boolean integerPart) {
            final var start = pos;
            while (pos < source.length() && source.charAt(pos) >= '0' && source.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == start) {
                throw fail("Unexpected token in JSON");
            }
            if (integerPart && source.charAt(start) == '0' && pos - start > 1) {
                throw fail("Unexpected number in JSON");
            }
        }

        private char peek() {
            return pos < source.length() ? source.charAt(pos) : '\0';
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw fail("Expected '" + expected + "' in JSON");
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < source.length()) {
                final var c = source.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private SyntaxErrorException fail(String message) {
            return new SyntaxErrorException(message + " at position " + pos);
        }
    }

    public static JsObject newHolder(JsObject objectProto) {
        final var holder = new JsObject();
        holder.setProto(objectProto);
        return holder;
    }
}
