package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.EJsonInterop;
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
    private static final EJson EJSON = new EJson();
    private static final int MAX_INDENT = 10;

    private record Replacer(JsValue function, List<String> allowList) {
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
        final var parsed = new JsonTextParser(text, objectProto).parseText();
        final var reviver = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!isCallable(reviver)) {
            return parsed;
        }
        final var holder = newHolder(objectProto);
        holder.defineValue("", parsed);
        return internalize(holder, "", reviver, ops, invoker);
    }

    // InternalizeJSONProperty: revive the children bottom-up, then hand the parent to the reviver.
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

    private static boolean isArray(JsValue value) {
        return value instanceof JsArray || (value instanceof JsProxy proxy && isArray(proxy.getTarget()));
    }

    private static void reviveChild(JsValue holder, String key, JsValue reviver, InterpreterOps ops, Invoker invoker) {
        final var revived = internalize(holder, key, reviver, ops, invoker);
        final var name = new JsString(key);
        if (revived instanceof JsUndefined) {
            ops.deleteMember(holder, name);
        } else {
            // CreateDataProperty, not [[Set]]: a proxy's defineProperty trap must be the one that
            // runs, and a rejected redefinition is ignored while an abrupt completion propagates.
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

    // A real JSON grammar rather than the EJson reader: EJson accepts text JSON rejects, collapses
    // -0 and would turn a "__proto__" key into a prototype assignment.
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

        // The integer part is the one place JSON forbids a leading zero.
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

    private static JsObject newHolder(JsObject objectProto) {
        final var holder = new JsObject();
        holder.setProto(objectProto);
        return holder;
    }

    private static JsValue stringify(List<JsValue> args, InterpreterOps ops, Invoker invoker, JsObject objectProto) {
        if (args.isEmpty()) {
            return JsUndefined.getInstance();
        }
        final var root = args.getFirst();
        final var holder = newHolder(objectProto);
        holder.set("", root);
        final var tree = toJsonTree(root, holder, "", replacerFor(args, ops), newSeen(), ops, invoker);
        if (tree == null) {
            return JsUndefined.getInstance();
        }
        return new JsString(EJSON.toJson(tree, indentFor(args, ops)));
    }

    private static Set<JsValue> newSeen() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static JsonBaseElement toJsonTree(JsValue raw, JsValue holder, String key, Replacer replacer,
            Set<JsValue> seen, InterpreterOps ops, Invoker invoker) {
        var value = applyToJson(raw, key, ops, invoker);
        if (replacer.function() != null) {
            value = invoker.call(replacer.function(), holder, List.of(new JsString(key), value));
        }
        // A boxed primitive unwraps to its primitive before serialization, and a proxy is walked
        // through its traps rather than serialized as its raw target.
        return switch (value) {
            case JsNumber number -> numberTree(number.getValue());
            case JsArray array -> arrayTree(array, replacer, seen, ops, invoker);
            // A boxed Number/String goes through ToNumber/ToString, so an overridden valueOf/toString
            // on the wrapper wins over the slot it was constructed with.
            case JsObject wrapper when wrapper.getPrimitive() instanceof JsNumber ->
                numberTree(JsCoercion.toNumber(wrapper, ops));
            case JsObject wrapper when wrapper.getPrimitive() instanceof JsString ->
                new org.techhouse.ejson.elements.JsonString(JsCoercion.toStr(wrapper, ops));
            case JsObject wrapper when wrapper.getPrimitive() != null -> EJsonInterop.toEjson(wrapper.getPrimitive());
            case JsObject object -> objectTree(object, replacer, seen, ops, invoker);
            case JsProxy proxy when isArray(proxy) -> proxyArrayTree(proxy, replacer, seen, ops, invoker);
            case JsProxy proxy when !isCallable(proxy) -> proxyObjectTree(proxy, replacer, seen, ops, invoker);
            default -> EJsonInterop.toEjson(value);
        };
    }

    private static JsonBaseElement numberTree(double value) {
        return Double.isFinite(value) ? new org.techhouse.ejson.elements.JsonNumber(value) : JsonNull.INSTANCE;
    }

    private static JsonBaseElement proxyArrayTree(JsProxy proxy, Replacer replacer, Set<JsValue> seen,
            InterpreterOps ops, Invoker invoker) {
        enter(proxy, seen);
        final var result = new JsonArray();
        final var length = (int) JsCoercion.toNumber(ops.getMember(proxy, new JsString("length")), ops);
        for (var i = 0; i < length; i++) {
            final var key = Integer.toString(i);
            final var child = toJsonTree(ops.getMember(proxy, new JsString(key)), proxy, key, replacer, seen, ops,
                    invoker);
            result.add(child == null ? JsonNull.INSTANCE : child);
        }
        seen.remove(proxy);
        return result;
    }

    private static JsonBaseElement proxyObjectTree(JsProxy proxy, Replacer replacer, Set<JsValue> seen,
            InterpreterOps ops, Invoker invoker) {
        enter(proxy, seen);
        final var result = new JsonObject();
        for (final var key : ops.ownKeys(proxy)) {
            if (!(key instanceof JsString name) || isFiltered(replacer, name.getValue())) {
                continue;
            }
            final var child = toJsonTree(ops.getMember(proxy, name), proxy, name.getValue(), replacer, seen, ops,
                    invoker);
            if (child != null) {
                result.add(name.getValue(), child);
            }
        }
        seen.remove(proxy);
        return result;
    }

    private static JsValue applyToJson(JsValue value, String key, InterpreterOps ops, Invoker invoker) {
        if (value instanceof JsUndefined || value instanceof JsNull) {
            return value;
        }
        final var toJson = ops.getMember(value, new JsString("toJSON"));
        if (isCallable(toJson)) {
            return invoker.call(toJson, value, List.of(new JsString(key)));
        }
        return value;
    }

    private static JsonBaseElement arrayTree(JsArray array, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
            Invoker invoker) {
        enter(array, seen);
        final var result = new JsonArray();
        final var length = array.length();
        for (var i = 0; i < length; i++) {
            final var key = Integer.toString(i);
            final var child = toJsonTree(ops.getMember(array, new JsString(key)), array, key, replacer, seen, ops,
                    invoker);
            result.add(child == null ? JsonNull.INSTANCE : child);
        }
        seen.remove(array);
        return result;
    }

    private static JsonBaseElement objectTree(JsObject object, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
            Invoker invoker) {
        enter(object, seen);
        final var result = new JsonObject();
        // A PropertyList replacer dictates both the membership *and* the order of the output keys.
        for (final var key : serializableKeys(object, replacer)) {
            final var child = toJsonTree(ownValue(object, key, ops), object, key, replacer, seen, ops, invoker);
            if (child != null) {
                result.add(key, child);
            }
        }
        seen.remove(object);
        return result;
    }

    private static List<String> serializableKeys(JsObject object, Replacer replacer) {
        if (replacer.allowList() != null) {
            return replacer.allowList();
        }
        final var keys = new ArrayList<String>();
        for (final var key : object.keys()) {
            if (object.isEnumerable(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean isFiltered(Replacer replacer, String key) {
        return replacer.allowList() != null && !replacer.allowList().contains(key);
    }

    private static void enter(JsValue value, Set<JsValue> seen) {
        if (!seen.add(value)) {
            throw new TypeErrorException("Converting circular structure to JSON");
        }
    }

    private static Replacer replacerFor(List<JsValue> args, InterpreterOps ops) {
        if (args.size() < 2) {
            return new Replacer(null, null);
        }
        final var replacer = args.get(1);
        if (isCallable(replacer)) {
            return new Replacer(replacer, null);
        }
        if (isArray(replacer)) {
            // The PropertyList keeps first-seen order and drops duplicates; a boxed String/Number
            // counts as its primitive, anything else is skipped entirely.
            final var keys = new ArrayList<String>();
            final var length = (long) JsCoercion.toNumber(ops.getMember(replacer, new JsString("length")), ops);
            for (var i = 0L; i < length; i++) {
                final var item = propertyListItem(ops.getMember(replacer, new JsString(Long.toString(i))), ops);
                if (item != null && !keys.contains(item)) {
                    keys.add(item);
                }
            }
            return new Replacer(null, keys);
        }
        return new Replacer(null, null);
    }

    private static String propertyListItem(JsValue element, InterpreterOps ops) {
        if (element instanceof JsString || element instanceof JsNumber) {
            return JsCoercion.toStr(element, ops);
        }
        if (element instanceof JsObject wrapper
                && (wrapper.getPrimitive() instanceof JsString || wrapper.getPrimitive() instanceof JsNumber)) {
            return JsCoercion.toStr(wrapper, ops);
        }
        return null;
    }

    private static String indentFor(List<JsValue> args, InterpreterOps ops) {
        if (args.size() < 3) {
            return null;
        }
        final var space = args.get(2);
        if (space instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsNumber) {
            return indentOfNumber(JsCoercion.toNumber(wrapper, ops));
        }
        if (space instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsString) {
            return indentOfString(JsCoercion.toStr(wrapper, ops));
        }
        return switch (space) {
            case JsNumber number -> indentOfNumber(number.getValue());
            case JsString string -> indentOfString(string.getValue());
            default -> null;
        };
    }

    private static String indentOfNumber(double value) {
        return " ".repeat(Double.isNaN(value) ? 0 : Math.clamp((long) value, 0, MAX_INDENT));
    }

    private static String indentOfString(String value) {
        return value.substring(0, Math.min(value.length(), MAX_INDENT));
    }
}
