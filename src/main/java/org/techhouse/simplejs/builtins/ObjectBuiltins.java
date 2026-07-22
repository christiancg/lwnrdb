package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
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
        object.set("create", new JsNativeFunction("create", (_, args) -> createObject(args)));
        object.set("getPrototypeOf", new JsNativeFunction("getPrototypeOf", (_, args) -> getPrototypeOf(args)));
        object.set("setPrototypeOf", new JsNativeFunction("setPrototypeOf", (_, args) -> setPrototypeOf(args)));
        object.set("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> defineProperty(args)));
        object.set("defineProperties", new JsNativeFunction("defineProperties", (_, args) -> defineProperties(args)));
        object.set("getOwnPropertyNames",
                new JsNativeFunction("getOwnPropertyNames", (_, args) -> getOwnPropertyNames(args)));
        object.set("getOwnPropertyDescriptor",
                new JsNativeFunction("getOwnPropertyDescriptor", (_, args) -> getOwnPropertyDescriptor(args)));
        object.set("fromEntries", new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args)));
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

    private static JsValue createObject(List<JsValue> args) {
        final var object = new JsObject();
        final var proto = first(args);
        if (proto instanceof JsObject protoObject) {
            object.setProto(protoObject);
        }
        if (args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props);
        }
        return object;
    }

    private static JsValue getPrototypeOf(List<JsValue> args) {
        if (first(args) instanceof JsObject object && object.getProto() != null) {
            return object.getProto();
        }
        return JsNull.getInstance();
    }

    private static JsValue setPrototypeOf(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.setProto(args.size() > 1 && args.get(1) instanceof JsObject proto ? proto : null);
        }
        return target;
    }

    private static JsValue defineProperty(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object && args.size() > 2 && args.get(2) instanceof JsObject descriptor) {
            applyDescriptor(object, JsCoercion.toStr(args.get(1)), descriptor);
        }
        return target;
    }

    private static JsValue defineProperties(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object && args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props);
        }
        return target;
    }

    private static void applyProperties(JsObject object, JsObject props) {
        for (final var entry : props.getProperties().entrySet()) {
            if (entry.getValue() instanceof JsObject descriptor) {
                applyDescriptor(object, entry.getKey(), descriptor);
            }
        }
    }

    private static void applyDescriptor(JsObject object, String key, JsObject descriptor) {
        if (object.isFrozen()) {
            return;
        }
        final var getter = descriptor.has("get") ? descriptor.get("get") : null;
        final var setter = descriptor.has("set") ? descriptor.get("set") : null;
        if (getter != null || setter != null) {
            object.defineAccessor(key, isCallable(getter) ? getter : null, isCallable(setter) ? setter : null);
        } else {
            object.set(key, descriptor.has("value") ? descriptor.get("value") : JsUndefined.getInstance());
        }
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static JsValue getOwnPropertyNames(List<JsValue> args) {
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
            result.push(new JsString("length"));
        }
        return result;
    }

    private static JsValue getOwnPropertyDescriptor(List<JsValue> args) {
        if (!(first(args) instanceof JsObject object) || args.size() < 2) {
            return JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(args.get(1));
        final var getter = object.getAccessorGetter(key);
        final var setter = object.getAccessorSetter(key);
        if (getter != null || setter != null) {
            final var descriptor = new JsObject();
            descriptor.set("get", getter == null ? JsUndefined.getInstance() : getter);
            descriptor.set("set", setter == null ? JsUndefined.getInstance() : setter);
            descriptor.set("enumerable", JsBoolean.of(true));
            descriptor.set("configurable", JsBoolean.of(!object.isFrozen()));
            return descriptor;
        }
        if (object.has(key)) {
            final var descriptor = new JsObject();
            descriptor.set("value", object.get(key));
            descriptor.set("writable", JsBoolean.of(!object.isFrozen()));
            descriptor.set("enumerable", JsBoolean.of(true));
            descriptor.set("configurable", JsBoolean.of(!object.isFrozen()));
            return descriptor;
        }
        return JsUndefined.getInstance();
    }

    private static JsValue fromEntries(List<JsValue> args) {
        final var result = new JsObject();
        if (first(args) instanceof JsArray entries) {
            for (final var element : entries.getElements()) {
                if (element instanceof JsArray pair && pair.length() > 0) {
                    final var value = pair.length() > 1 ? pair.get(1) : JsUndefined.getInstance();
                    result.set(JsCoercion.toStr(pair.get(0)), value);
                }
            }
        }
        return result;
    }

    private static JsValue first(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }
}
