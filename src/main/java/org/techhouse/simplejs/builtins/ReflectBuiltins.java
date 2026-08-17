package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ReflectBuiltins {
    private static final double MAX_ARGUMENTS = Integer.MAX_VALUE;

    private ReflectBuiltins() {
    }

    public static JsObject create(InterpreterOps ops) {
        final var reflect = new JsObject();
        Intrinsics.defineHidden(reflect, "get", new JsNativeFunction("get", (_, args) -> get(ops, args)));
        Intrinsics.defineHidden(reflect, "set", new JsNativeFunction("set", (_, args) -> set(ops, args)));
        Intrinsics.defineHidden(reflect, "has",
                new JsNativeFunction("has", (_, args) -> JsBoolean.of(ops.has(target(args, "has"), key(ops, args)))));
        Intrinsics.defineHidden(reflect, "deleteProperty", new JsNativeFunction("deleteProperty",
                (_, args) -> JsBoolean.of(ops.deleteMember(target(args, "deleteProperty"), key(ops, args)))));
        Intrinsics.defineHidden(reflect, "ownKeys", new JsNativeFunction("ownKeys",
                (_, args) -> new JsArray(new ArrayList<>(ops.ownKeys(target(args, "ownKeys"))))));
        Intrinsics.defineHidden(reflect, "apply", new JsNativeFunction("apply", (_, args) -> apply(ops, args)));
        Intrinsics.defineHidden(reflect, "construct",
                new JsNativeFunction("construct", (_, args) -> construct(ops, args)));
        Intrinsics.defineHidden(reflect, "getPrototypeOf", new JsNativeFunction("getPrototypeOf",
                (_, args) -> ops.getPrototypeOf(target(args, "getPrototypeOf"))));
        Intrinsics.defineHidden(reflect, "setPrototypeOf",
                new JsNativeFunction("setPrototypeOf", (_, args) -> setPrototypeOf(ops, args)));
        Intrinsics.defineHidden(reflect, "isExtensible", new JsNativeFunction("isExtensible",
                (_, args) -> JsBoolean.of(ops.isExtensible(target(args, "isExtensible")))));
        Intrinsics.defineHidden(reflect, "preventExtensions", new JsNativeFunction("preventExtensions",
                (_, args) -> JsBoolean.of(ops.preventExtensions(target(args, "preventExtensions")))));
        Intrinsics.defineHidden(reflect, "defineProperty",
                new JsNativeFunction("defineProperty", (_, args) -> defineProperty(ops, args)));
        Intrinsics.defineHidden(reflect, "getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(target(args, "getOwnPropertyDescriptor"), key(ops, args))));
        Intrinsics.defineNamespaceTag(reflect, "Reflect");
        return reflect;
    }

    private static JsValue get(InterpreterOps ops, List<JsValue> args) {
        final var target = target(args, "get");
        final var key = key(ops, args);
        return args.size() > 2 ? ops.getMemberWithReceiver(target, key, args.get(2)) : ops.getMember(target, key);
    }

    private static JsValue set(InterpreterOps ops, List<JsValue> args) {
        final var target = target(args, "set");
        final var key = key(ops, args);
        final var value = arg(args, 2);
        return JsBoolean.of(args.size() > 3 && args.get(3) != target
                ? ordinarySet(ops, target, key, value, args.get(3))
                : ops.setMember(target, key, value));
    }

    // OrdinarySet with a Receiver that is not the target: the write lands on the receiver, and the
    // target is consulted only for the descriptor that authorises it.
    private static boolean ordinarySet(InterpreterOps ops, JsValue target, JsValue key, JsValue value,
            JsValue receiver) {
        if (target instanceof JsProxy) {
            return ops.setMemberWithReceiver(target, key, value, receiver);
        }
        if (!(ops.getOwnPropertyDescriptor(target, key) instanceof JsObject own)) {
            final var parent = ops.getPrototypeOf(target);
            return InterpreterUtils.isObjectLike(parent)
                    ? ordinarySet(ops, parent, key, value, receiver)
                    : defineOnReceiver(ops, key, value, receiver);
        }
        if (own.has("get") || own.has("set")) {
            final var setter = own.get("set");
            if (!InterpreterUtils.isCallable(setter)) {
                return false;
            }
            ops.call(setter, receiver, List.of(value));
            return true;
        }
        return JsCoercion.toBoolean(own.get("writable")) && defineOnReceiver(ops, key, value, receiver);
    }

    private static boolean defineOnReceiver(InterpreterOps ops, JsValue key, JsValue value, JsValue receiver) {
        if (!InterpreterUtils.isObjectLike(receiver)) {
            return false;
        }
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        if (ops.getOwnPropertyDescriptor(receiver, key) instanceof JsObject existing) {
            if (existing.has("get") || existing.has("set") || !JsCoercion.toBoolean(existing.get("writable"))) {
                return false;
            }
        } else {
            descriptor.set("writable", JsBoolean.TRUE);
            descriptor.set("enumerable", JsBoolean.TRUE);
            descriptor.set("configurable", JsBoolean.TRUE);
        }
        try {
            return ops.defineProperty(receiver, key, descriptor);
        } catch (TypeErrorException ignored) {
            return false;
        }
    }

    // OrdinarySetPrototypeOf answers false rather than throwing; only Object.setPrototypeOf turns
    // that into a TypeError, so the ordinary checks live here instead of behind the shared seam.
    private static JsValue setPrototypeOf(InterpreterOps ops, List<JsValue> args) {
        final var target = target(args, "setPrototypeOf");
        final var proto = arg(args, 1);
        if (!InterpreterUtils.isObjectLike(proto) && !(proto instanceof JsNull)) {
            throw new TypeErrorException(
                    "Reflect.setPrototypeOf called with a prototype that is neither object " + "nor null");
        }
        if (target instanceof JsProxy) {
            return JsBoolean.of(ops.setPrototypeOf(target, proto));
        }
        final var current = target.getProto();
        if (current == proto || (current == null && proto instanceof JsNull)) {
            return JsBoolean.TRUE;
        }
        if (!target.isExtensible()) {
            return JsBoolean.FALSE;
        }
        for (var walk = proto instanceof JsNull ? null : proto; walk != null; walk = walk.getProto()) {
            if (walk == target) {
                return JsBoolean.FALSE;
            }
        }
        return JsBoolean.of(ops.setPrototypeOf(target, proto));
    }

    private static JsValue apply(InterpreterOps ops, List<JsValue> args) {
        final var target = arg(args, 0);
        if (!InterpreterUtils.isCallable(target) && !(target instanceof JsProxy proxy && proxy.isCallable())) {
            throw new TypeErrorException("Reflect.apply called on non-callable target");
        }
        return ops.call(target, arg(args, 1), argumentsList(ops, arg(args, 2)));
    }

    private static JsValue construct(InterpreterOps ops, List<JsValue> args) {
        final var target = arg(args, 0);
        if (!InterpreterUtils.isConstructor(target)) {
            throw new TypeErrorException("Reflect.construct called on non-constructor target");
        }
        final var newTarget = args.size() > 2 ? args.get(2) : target;
        if (!InterpreterUtils.isConstructor(newTarget)) {
            throw new TypeErrorException("Reflect.construct called with a non-constructor newTarget");
        }
        return ops.construct(target, argumentsList(ops, arg(args, 1)), newTarget);
    }

    // CreateListFromArrayLike: any object is walked by length + indexed Get; a primitive is a
    // TypeError rather than the empty list a literal-JsArray-only check would silently produce.
    private static List<JsValue> argumentsList(InterpreterOps ops, JsValue value) {
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("CreateListFromArrayLike called on non-object");
        }
        if (value instanceof JsArray array) {
            return new ArrayList<>(array.getElements());
        }
        final var length = JsCoercion.toNumber(ops.getMember(value, new JsString("length")), ops);
        if (Double.isNaN(length) || length <= 0) {
            return new ArrayList<>();
        }
        if (length > MAX_ARGUMENTS) {
            throw new TypeErrorException("Arguments list length exceeds the supported maximum");
        }
        final var list = new ArrayList<JsValue>((int) length);
        for (var i = 0; i < (int) length; i++) {
            list.add(ops.getMember(value, new JsString(Integer.toString(i))));
        }
        return list;
    }

    // A proxy's [[DefineOwnProperty]] reports a refusing trap through its return value and reserves
    // the TypeError for a violated invariant, so that one must propagate; the ordinary path has only
    // the throw to signal a rejected definition, which Reflect has to turn back into false.
    private static JsValue defineProperty(InterpreterOps ops, List<JsValue> args) {
        final var target = target(args, "defineProperty");
        final var key = key(ops, args);
        if (target instanceof JsProxy) {
            return JsBoolean.of(ops.defineProperty(target, key, arg(args, 2)));
        }
        try {
            return JsBoolean.of(ops.defineProperty(target, key, arg(args, 2)));
        } catch (TypeErrorException ignored) {
            return JsBoolean.FALSE;
        }
    }

    private static JsValue target(List<JsValue> args, String method) {
        final var target = arg(args, 0);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Reflect." + method + " called on non-object");
        }
        return target;
    }

    private static JsValue key(InterpreterOps ops, List<JsValue> args) {
        return JsCoercion.toPropertyKey(arg(args, 1), ops);
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
