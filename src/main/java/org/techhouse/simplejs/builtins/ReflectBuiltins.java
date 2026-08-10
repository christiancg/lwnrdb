package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ReflectBuiltins {
    private ReflectBuiltins() {
    }

    public static JsObject create(InterpreterOps ops) {
        final var reflect = new JsObject();
        reflect.set("get",
                new JsNativeFunction("get",
                        (_, args) -> args.size() > 2
                                ? ops.getMemberWithReceiver(arg(args, 0), arg(args, 1), arg(args, 2))
                                : ops.getMember(arg(args, 0), arg(args, 1))));
        reflect.set("set",
                new JsNativeFunction("set",
                        (_, args) -> JsBoolean.of(args.size() > 3
                                ? ops.setMemberWithReceiver(arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3))
                                : ops.setMember(arg(args, 0), arg(args, 1), arg(args, 2)))));
        reflect.set("has", new JsNativeFunction("has", (_, args) -> JsBoolean.of(ops.has(arg(args, 0), arg(args, 1)))));
        reflect.set("deleteProperty", new JsNativeFunction("deleteProperty",
                (_, args) -> JsBoolean.of(ops.deleteMember(arg(args, 0), arg(args, 1)))));
        reflect.set("ownKeys", new JsNativeFunction("ownKeys", (_, args) -> new JsArray(ops.ownKeys(arg(args, 0)))));
        reflect.set("apply", new JsNativeFunction("apply", (_, args) -> apply(ops, args)));
        reflect.set("construct", new JsNativeFunction("construct", (_, args) -> construct(ops, args)));
        reflect.set("getPrototypeOf",
                new JsNativeFunction("getPrototypeOf", (_, args) -> ops.getPrototypeOf(arg(args, 0))));
        reflect.set("setPrototypeOf", new JsNativeFunction("setPrototypeOf",
                (_, args) -> JsBoolean.of(ops.setPrototypeOf(arg(args, 0), arg(args, 1)))));
        reflect.set("isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(arg(args, 0)))));
        reflect.set("preventExtensions", new JsNativeFunction("preventExtensions",
                (_, args) -> JsBoolean.of(ops.preventExtensions(arg(args, 0)))));
        reflect.set("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> defineProperty(ops, args)));
        reflect.set("getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(arg(args, 0), arg(args, 1))));
        return reflect;
    }

    private static JsValue apply(InterpreterOps ops, List<JsValue> args) {
        return ops.call(arg(args, 0), arg(args, 1), toList(arg(args, 2)));
    }

    private static JsValue construct(InterpreterOps ops, List<JsValue> args) {
        return ops.construct(arg(args, 0), toList(arg(args, 1)));
    }

    private static JsValue defineProperty(InterpreterOps ops, List<JsValue> args) {
        try {
            ops.defineProperty(arg(args, 0), arg(args, 1), arg(args, 2));
            return JsBoolean.TRUE;
        } catch (TypeErrorException ignored) {
            return JsBoolean.FALSE;
        }
    }

    private static List<JsValue> toList(JsValue value) {
        return value instanceof JsArray array ? new ArrayList<>(array.getElements()) : new ArrayList<>();
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
