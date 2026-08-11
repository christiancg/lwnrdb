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
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
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
        final var json = new JsObject();
        json.set("parse", new JsNativeFunction("parse", (_, args) -> parse(args, invoker)));
        json.set("stringify", new JsNativeFunction("stringify", (_, args) -> stringify(args, ops, invoker)));
        return json;
    }

    private static JsValue parse(List<JsValue> args, Invoker invoker) {
        final var source = args.isEmpty() ? "undefined" : args.getFirst();
        if (!(source instanceof JsString string)) {
            throw new SyntaxErrorException("Unexpected token in JSON");
        }
        final JsonBaseElement element;
        try {
            element = EJSON.fromJson("{\"v\":" + string.getValue() + "}", JsonObject.class).get("v");
        } catch (RuntimeException e) {
            throw new SyntaxErrorException("Unexpected token in JSON: " + e.getMessage());
        }
        final var parsed = EJsonInterop.fromEjson(element);
        final var reviver = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!isCallable(reviver)) {
            return parsed;
        }
        final var holder = new JsObject();
        holder.set("", parsed);
        return internalize(holder, "", reviver, invoker);
    }

    // InternalizeJSONProperty: revive the children bottom-up, then hand the parent to the reviver.
    private static JsValue internalize(JsObject holder, String key, JsValue reviver, Invoker invoker) {
        final var value = holder.get(key);
        switch (value) {
            case JsArray array -> {
                for (var i = 0; i < array.length(); i++) {
                    reviveInto(array, i, reviver, invoker);
                }
            }
            case JsObject object -> {
                for (final var child : List.copyOf(object.keys())) {
                    final var revived = internalize(object, child, reviver, invoker);
                    if (revived instanceof JsUndefined) {
                        object.delete(child);
                    } else {
                        object.set(child, revived);
                    }
                }
            }
            default -> {
            }
        }
        return invoker.call(reviver, holder, List.of(new JsString(key), value));
    }

    private static void reviveInto(JsArray array, int index, JsValue reviver, Invoker invoker) {
        final var element = new JsObject();
        final var name = Integer.toString(index);
        element.set(name, array.get(index));
        array.set(index, internalize(element, name, reviver, invoker));
    }

    private static JsValue stringify(List<JsValue> args, InterpreterOps ops, Invoker invoker) {
        if (args.isEmpty()) {
            return JsUndefined.getInstance();
        }
        final var root = args.getFirst();
        final var holder = new JsObject();
        holder.set("", root);
        final var tree = toJsonTree(root, holder, "", replacerFor(args), newSeen(), ops, invoker);
        if (tree == null) {
            return JsUndefined.getInstance();
        }
        return new JsString(EJSON.toJson(tree, indentFor(args)));
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
        return switch (value) {
            case JsArray array -> arrayTree(array, replacer, seen, ops, invoker);
            case JsObject object -> objectTree(object, replacer, seen, ops, invoker);
            default -> EJsonInterop.toEjson(value);
        };
    }

    private static JsValue applyToJson(JsValue value, String key, InterpreterOps ops, Invoker invoker) {
        if (value instanceof JsUndefined || value instanceof JsNull) {
            return value;
        }
        final var toJson = ops.getMember(value, new JsString("toJSON"));
        if (toJson instanceof JsFunction || toJson instanceof JsNativeFunction) {
            return invoker.call(toJson, value, List.of(new JsString(key)));
        }
        return value;
    }

    private static JsonBaseElement arrayTree(JsArray array, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
            Invoker invoker) {
        enter(array, seen);
        final var result = new JsonArray();
        final var elements = array.getElements();
        for (var i = 0; i < elements.size(); i++) {
            final var child = toJsonTree(elements.get(i), array, Integer.toString(i), replacer, seen, ops, invoker);
            result.add(child == null ? JsonNull.INSTANCE : child);
        }
        seen.remove(array);
        return result;
    }

    private static JsonBaseElement objectTree(JsObject object, Replacer replacer, Set<JsValue> seen, InterpreterOps ops,
            Invoker invoker) {
        enter(object, seen);
        final var result = new JsonObject();
        for (final var key : object.keys()) {
            if (!object.isEnumerable(key) || isFiltered(replacer, key)) {
                continue;
            }
            final var child = toJsonTree(ownValue(object, key, ops), object, key, replacer, seen, ops, invoker);
            if (child != null) {
                result.add(key, child);
            }
        }
        seen.remove(object);
        return result;
    }

    private static boolean isFiltered(Replacer replacer, String key) {
        return replacer.allowList() != null && !replacer.allowList().contains(key);
    }

    private static void enter(JsValue value, Set<JsValue> seen) {
        if (!seen.add(value)) {
            throw new TypeErrorException("Converting circular structure to JSON");
        }
    }

    private static Replacer replacerFor(List<JsValue> args) {
        if (args.size() < 2) {
            return new Replacer(null, null);
        }
        final var replacer = args.get(1);
        if (replacer instanceof JsFunction || replacer instanceof JsNativeFunction) {
            return new Replacer(replacer, null);
        }
        if (replacer instanceof JsArray array) {
            final var keys = new ArrayList<String>();
            for (final var element : array.getElements()) {
                if (element instanceof JsString || element instanceof JsNumber) {
                    keys.add(JsCoercion.toStr(element));
                }
            }
            return new Replacer(null, keys);
        }
        return new Replacer(null, null);
    }

    private static String indentFor(List<JsValue> args) {
        if (args.size() < 3) {
            return null;
        }
        return switch (args.get(2)) {
            case JsNumber number -> " ".repeat(Math.clamp((int) number.getValue(), 0, MAX_INDENT));
            case JsString string -> string.getValue().substring(0, Math.min(string.getValue().length(), MAX_INDENT));
            default -> null;
        };
    }
}
