package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.values.JsLimits.MAX_LIST_LENGTH;

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
                ? OrdinarySet.set(ops, target, key, value, args.get(3))
                : ops.setMember(target, key, value));
    }

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
        if (length > MAX_LIST_LENGTH) {
            throw new TypeErrorException("Arguments list length exceeds the supported maximum");
        }
        final var list = new ArrayList<JsValue>((int) length);
        for (var i = 0; i < (int) length; i++) {
            list.add(ops.getMember(value, new JsString(Integer.toString(i))));
        }
        return list;
    }

    private static JsValue defineProperty(InterpreterOps ops, List<JsValue> args) {
        final var target = target(args, "defineProperty");
        final var key = key(ops, args);
        if (target instanceof JsProxy proxy && hasDefinePropertyTrap(ops, proxy)) {
            return JsBoolean.of(ops.defineProperty(target, key, arg(args, 2)));
        }
        try {
            return JsBoolean.of(ops.defineProperty(target, key, arg(args, 2)));
        } catch (TypeErrorException ignored) {
            return JsBoolean.FALSE;
        }
    }

    private static boolean hasDefinePropertyTrap(InterpreterOps ops, JsProxy proxy) {
        final var trap = ops.getMember(proxy.getHandler(), new JsString("defineProperty"));
        return !(trap instanceof JsUndefined) && !(trap instanceof JsNull);
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

}
