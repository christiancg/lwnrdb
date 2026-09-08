package org.techhouse.simplejs.builtins.json;

import static org.techhouse.simplejs.builtins.JsonBuiltins.EJSON;
import static org.techhouse.simplejs.builtins.JsonBuiltins.MAX_INDENT;
import static org.techhouse.simplejs.builtins.JsonBuiltins.isArray;
import static org.techhouse.simplejs.builtins.JsonBuiltins.newHolder;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.JsonBuiltins.Replacer;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsonStringify {
    public static JsValue stringify(List<JsValue> args, InterpreterOps ops, Invoker invoker, JsObject objectProto) {
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
        final var text = EJSON.toJson(tree, indentFor(args, ops));
        InterpreterOps.chargeChars(ops, text.length());
        return new JsString(text);
    }

    public static Set<JsValue> newSeen() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public static JsonBaseElement toJsonTree(JsValue raw, JsValue holder, String key, Replacer replacer,
            Set<JsValue> seen, InterpreterOps ops, Invoker invoker) {
        var value = applyToJson(raw, key, ops, invoker);
        if (replacer.function() != null) {
            value = invoker.call(replacer.function(), holder, List.of(new JsString(key), value));
        }
        return switch (value) {
            case JsNumber number -> numberTree(number.getValue());
            case JsArray array -> arrayTree(array, replacer, seen, ops, invoker);
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

    public static JsonBaseElement numberTree(double value) {
        return Double.isFinite(value) ? new org.techhouse.ejson.elements.JsonNumber(value) : JsonNull.INSTANCE;
    }

    public static JsonBaseElement proxyArrayTree(JsProxy proxy, Replacer replacer, Set<JsValue> seen,
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

    public static JsonBaseElement proxyObjectTree(JsProxy proxy, Replacer replacer, Set<JsValue> seen,
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

    public static JsValue applyToJson(JsValue value, String key, InterpreterOps ops, Invoker invoker) {
        if (value instanceof JsUndefined || value instanceof JsNull) {
            return value;
        }
        final var toJson = ops.getMember(value, new JsString("toJSON"));
        if (isCallable(toJson)) {
            return invoker.call(toJson, value, List.of(new JsString(key)));
        }
        return value;
    }

    public static JsonBaseElement arrayTree(JsArray array, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
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

    public static JsonBaseElement objectTree(JsObject object, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
            Invoker invoker) {
        enter(object, seen);
        final var result = new JsonObject();
        for (final var key : serializableKeys(object, replacer)) {
            final var child = toJsonTree(ownValue(object, key, ops), object, key, replacer, seen, ops, invoker);
            if (child != null) {
                result.add(key, child);
            }
        }
        seen.remove(object);
        return result;
    }

    public static List<String> serializableKeys(JsObject object, Replacer replacer) {
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

    public static boolean isFiltered(Replacer replacer, String key) {
        return replacer.allowList() != null && !replacer.allowList().contains(key);
    }

    public static void enter(JsValue value, Set<JsValue> seen) {
        if (!seen.add(value)) {
            throw new TypeErrorException("Converting circular structure to JSON");
        }
    }

    public static Replacer replacerFor(List<JsValue> args, InterpreterOps ops) {
        if (args.size() < 2) {
            return new Replacer(null, null);
        }
        final var replacer = args.get(1);
        if (isCallable(replacer)) {
            return new Replacer(replacer, null);
        }
        if (isArray(replacer)) {
            final var keys = new ArrayList<String>();
            final var length = (long) JsCoercion.toNumber(ops.getMember(replacer, new JsString("length")), ops);
            InterpreterOps.chargeElements(ops, length);
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

    public static String propertyListItem(JsValue element, InterpreterOps ops) {
        if (element instanceof JsString || element instanceof JsNumber) {
            return JsCoercion.toStr(element, ops);
        }
        if (element instanceof JsObject wrapper
                && (wrapper.getPrimitive() instanceof JsString || wrapper.getPrimitive() instanceof JsNumber)) {
            return JsCoercion.toStr(wrapper, ops);
        }
        return null;
    }

    public static String indentFor(List<JsValue> args, InterpreterOps ops) {
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

    public static String indentOfNumber(double value) {
        return " ".repeat(Double.isNaN(value) ? 0 : Math.clamp((long) value, 0, MAX_INDENT));
    }

    public static String indentOfString(String value) {
        return value.substring(0, Math.min(value.length(), MAX_INDENT));
    }

    private JsonStringify() {
    }
}
