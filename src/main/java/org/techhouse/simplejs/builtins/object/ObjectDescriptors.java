package org.techhouse.simplejs.builtins.object;

import static org.techhouse.simplejs.builtins.ObjectBuiltins.argAt;
import static org.techhouse.simplejs.builtins.ObjectBuiltins.attachObjectProto;
import static org.techhouse.simplejs.builtins.ObjectBuiltins.first;
import static org.techhouse.simplejs.builtins.ObjectBuiltins.isNotCallable;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;

public final class ObjectDescriptors {
    public static JsValue toPropertyKey(JsValue value, InterpreterOps ops) {
        return JsCoercion.toPropertyKey(value, ops);
    }

    public static PropertyDescriptor toPropertyDescriptor(JsValue descriptor, InterpreterOps ops) {
        final Boolean enumerable = descHas(descriptor, "enumerable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                : null;
        final Boolean configurable = descHas(descriptor, "configurable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                : null;
        final var hasValue = descHas(descriptor, "value", ops);
        final var value = hasValue ? descGet(descriptor, "value", ops) : null;
        final var hasWritable = descHas(descriptor, "writable", ops);
        final Boolean writable = hasWritable ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops)) : null;
        final var getter = descHas(descriptor, "get", ops) ? descGet(descriptor, "get", ops) : null;
        requireAccessorField(getter, "Getter");
        final var setter = descHas(descriptor, "set", ops) ? descGet(descriptor, "set", ops) : null;
        requireAccessorField(setter, "Setter");
        if ((getter != null || setter != null) && (hasValue || hasWritable)) {
            throw new TypeErrorException("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute");
        }
        return new PropertyDescriptor(value, getter, setter, writable, enumerable, configurable);
    }

    public static void requireAccessorField(JsValue value, String label) {
        if (value != null && isNotCallable(value) && !(value instanceof JsUndefined)) {
            throw new TypeErrorException(label + " must be a function");
        }
    }

    public static JsValue createObject(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var proto = first(args);
        if (!InterpreterUtils.isObjectLike(proto) && !(proto instanceof JsNull)) {
            throw new TypeErrorException("Object prototype may only be an Object or null: " + JsCoercion.toStr(proto));
        }
        final var object = new JsObject();
        object.setProto(InterpreterUtils.isObjectLike(proto) ? proto : null);
        if (args.size() > 1 && !(args.get(1) instanceof JsUndefined)) {
            if (args.get(1) instanceof JsNull) {
                throw new TypeErrorException("Cannot convert undefined or null to object");
            }
            applyPropertiesFrom(object, intrinsics.toObject(args.get(1)), ops);
        }
        return object;
    }

    public static JsValue getPrototypeOf(List<JsValue> args) {
        final var proto = first(args).getProto();
        return proto == null ? JsNull.getInstance() : proto;
    }

    public static boolean trySetPrototypeOf(JsValue target, JsValue protoArg, Intrinsics intrinsics) {
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Object.setPrototypeOf called on null or undefined");
        }
        if (!InterpreterUtils.isObjectLike(protoArg) && !(protoArg instanceof JsNull)) {
            throw new TypeErrorException(
                    "Object prototype may only be an Object or null: " + JsCoercion.toStr(protoArg));
        }
        if (!InterpreterUtils.isObjectLike(target)) {
            return true;
        }
        final var proto = InterpreterUtils.isObjectLike(protoArg) ? protoArg : null;
        if (proto == target.getProto()) {
            return true;
        }
        if (intrinsics != null && target == intrinsics.objectProto) {
            return false;
        }
        if (!target.isExtensible()) {
            return false;
        }
        for (var walk = proto; walk != null; walk = walk.getProto()) {
            if (walk == target) {
                return false;
            }
        }
        target.setProto(proto);
        return true;
    }

    public static JsValue defineProperty(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Object.defineProperty called on non-object");
        }
        final var propertyKey = toPropertyKey(args.get(1), ops);
        if (args.size() < 3 || !InterpreterUtils.isObjectLike(args.get(2))) {
            throw new TypeErrorException("Property description must be an object");
        }
        final var descriptor = toPropertyDescriptor(args.get(2), ops);
        JsArray.withLengthCoercionOps(ops, () -> target.defineOwnProperty(propertyKey, descriptor));
        return target;
    }

    public static JsValue defineProperties(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Object.defineProperties called on non-object");
        }
        if (args.size() < 2 || InterpreterUtils.isNullish(args.get(1))) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        applyPropertiesFrom(target, args.get(1), ops);
        return target;
    }

    public static void applyPropertiesFrom(JsValue target, JsValue props, InterpreterOps ops) {
        for (final var key : ops.ownKeys(props)) {
            if (!isEnumerableOwnKey(props, key, ops)) {
                continue;
            }
            defineProperty(List.of(target, key, ops.getMember(props, key)), ops);
        }
    }

    public static boolean isEnumerableOwnKey(JsValue props, JsValue key, InterpreterOps ops) {
        return ops.getOwnPropertyDescriptor(props, key) instanceof JsObject descriptor
                && JsCoercion.toBoolean(descriptor.get("enumerable"));
    }

    public static boolean descHas(JsValue descriptor, String key, InterpreterOps ops) {
        return ops.has(descriptor, new JsString(key));
    }

    public static JsValue descGet(JsValue descriptor, String key, InterpreterOps ops) {
        return ops.getMember(descriptor, new JsString(key));
    }

    public static JsValue getOwnPropertyDescriptors(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var target = first(args);
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var result = new JsObject();
        result.setProto(intrinsics.objectProto);
        for (final var key : ops.ownKeys(target)) {
            final var descriptor = attachObjectProto(ops.getOwnPropertyDescriptor(target, key), intrinsics);
            if (descriptor instanceof JsUndefined) {
                continue;
            }
            if (key instanceof JsSymbol symbol) {
                result.setSymbol(symbol, descriptor);
            } else {
                result.set(JsCoercion.toStr(key), descriptor);
            }
        }
        final var table = target.ownProperties();
        if (table != null) {
            for (final var symbol : table.symbolKeys()) {
                result.setSymbol(symbol, attachObjectProto(ops.getOwnPropertyDescriptor(target, symbol), intrinsics));
            }
        }
        return result;
    }

    public static JsValue getOwnPropertyDescriptor(List<JsValue> args) {
        final var target = first(args);
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var key = argAt(args, 1);
        var descriptor = target.getOwnProperty(key);
        if (descriptor == null && target instanceof JsObject wrapper && wrapper.getPrimitive() != null) {
            descriptor = wrapper.getPrimitive().getOwnProperty(key);
        }
        return descriptor == null ? JsUndefined.getInstance() : describe(descriptor);
    }

    public static JsValue describe(PropertyDescriptor descriptor) {
        final var result = new JsObject();
        if (descriptor.isAccessorDescriptor()) {
            result.set("get", descriptor.getter());
            result.set("set", descriptor.setter());
        } else {
            result.set("value", descriptor.value());
            result.set("writable", JsBoolean.of(descriptor.writableOr(false)));
        }
        result.set("enumerable", JsBoolean.of(descriptor.enumerableOr(false)));
        result.set("configurable", JsBoolean.of(descriptor.configurableOr(false)));
        return result;
    }

    private ObjectDescriptors() {
    }
}
