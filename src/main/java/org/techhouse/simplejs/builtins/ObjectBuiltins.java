package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    public static JsObject create() {
        final var object = new JsObject();
        object.set("keys", new JsNativeFunction("keys", (_, args) -> keys(args)));
        object.set("values", new JsNativeFunction("values", (_, args) -> values(args)));
        object.set("entries", new JsNativeFunction("entries", (_, args) -> entries(args)));
        object.set("assign", new JsNativeFunction("assign", (_, args) -> assign(args)));
        object.set("freeze", new JsNativeFunction("freeze", (_, args) -> freeze(args)));
        return object;
    }

    private static JsValue keys(List<JsValue> args) {
        final var result = new JsArray();
        final var target = first(args);
        if (target instanceof JsObject object) {
            for (final var key : object.keys()) {
                result.push(new JsString(key));
            }
        } else if (target instanceof JsArray array) {
            for (var i = 0; i < array.length(); i++) {
                result.push(new JsString(Integer.toString(i)));
            }
        }
        return result;
    }

    private static JsValue values(List<JsValue> args) {
        final var result = new JsArray();
        final var target = first(args);
        if (target instanceof JsObject object) {
            for (final var value : object.getProperties().values()) {
                result.push(value);
            }
        } else if (target instanceof JsArray array) {
            for (final var value : array.getElements()) {
                result.push(value);
            }
        }
        return result;
    }

    private static JsValue entries(List<JsValue> args) {
        final var result = new JsArray();
        final var target = first(args);
        if (target instanceof JsObject object) {
            for (final var entry : object.getProperties().entrySet()) {
                result.push(new JsArray(List.of(new JsString(entry.getKey()), entry.getValue())));
            }
        } else if (target instanceof JsArray array) {
            final var elements = array.getElements();
            for (var i = 0; i < elements.size(); i++) {
                result.push(new JsArray(List.of(new JsString(Integer.toString(i)), elements.get(i))));
            }
        }
        return result;
    }

    private static JsValue assign(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsObject target)) {
            return first(args);
        }
        for (var i = 1; i < args.size(); i++) {
            if (args.get(i) instanceof JsObject source) {
                for (final var entry : source.getProperties().entrySet()) {
                    target.set(entry.getKey(), entry.getValue());
                }
            }
        }
        return target;
    }

    private static JsValue freeze(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.freeze();
        }
        return target;
    }

    private static JsValue first(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }
}
