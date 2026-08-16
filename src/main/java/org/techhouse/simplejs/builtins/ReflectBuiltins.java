package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
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
        Intrinsics.defineHidden(reflect, "get",
                new JsNativeFunction("get",
                        (_, args) -> args.size() > 2
                                ? ops.getMemberWithReceiver(arg(args, 0), arg(args, 1), arg(args, 2))
                                : ops.getMember(arg(args, 0), arg(args, 1))));
        Intrinsics.defineHidden(reflect, "set",
                new JsNativeFunction("set",
                        (_, args) -> JsBoolean.of(args.size() > 3
                                ? ops.setMemberWithReceiver(arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3))
                                : ops.setMember(arg(args, 0), arg(args, 1), arg(args, 2)))));
        Intrinsics.defineHidden(reflect, "has",
                new JsNativeFunction("has", (_, args) -> JsBoolean.of(ops.has(arg(args, 0), arg(args, 1)))));
        Intrinsics.defineHidden(reflect, "deleteProperty", new JsNativeFunction("deleteProperty",
                (_, args) -> JsBoolean.of(ops.deleteMember(arg(args, 0), arg(args, 1)))));
        Intrinsics.defineHidden(reflect, "ownKeys",
                new JsNativeFunction("ownKeys", (_, args) -> new JsArray(ops.ownKeys(arg(args, 0)))));
        Intrinsics.defineHidden(reflect, "apply", new JsNativeFunction("apply", (_, args) -> apply(ops, args)));
        Intrinsics.defineHidden(reflect, "construct",
                new JsNativeFunction("construct", (_, args) -> construct(ops, args)));
        Intrinsics.defineHidden(reflect, "getPrototypeOf",
                new JsNativeFunction("getPrototypeOf", (_, args) -> ops.getPrototypeOf(arg(args, 0))));
        Intrinsics.defineHidden(reflect, "setPrototypeOf", new JsNativeFunction("setPrototypeOf",
                (_, args) -> JsBoolean.of(ops.setPrototypeOf(arg(args, 0), arg(args, 1)))));
        Intrinsics.defineHidden(reflect, "isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(arg(args, 0)))));
        Intrinsics.defineHidden(reflect, "preventExtensions", new JsNativeFunction("preventExtensions",
                (_, args) -> JsBoolean.of(ops.preventExtensions(arg(args, 0)))));
        Intrinsics.defineHidden(reflect, "defineProperty",
                new JsNativeFunction("defineProperty", (_, args) -> defineProperty(ops, args)));
        Intrinsics.defineHidden(reflect, "getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(arg(args, 0), arg(args, 1))));
        Intrinsics.defineNamespaceTag(reflect, "Reflect");
        return reflect;
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

    private static JsValue defineProperty(InterpreterOps ops, List<JsValue> args) {
        try {
            ops.defineProperty(arg(args, 0), arg(args, 1), arg(args, 2));
            return JsBoolean.TRUE;
        } catch (TypeErrorException ignored) {
            return JsBoolean.FALSE;
        }
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
