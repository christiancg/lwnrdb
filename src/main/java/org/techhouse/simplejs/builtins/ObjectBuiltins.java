package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.object.ObjectDescriptors.createObject;
import static org.techhouse.simplejs.builtins.object.ObjectDescriptors.defineProperties;
import static org.techhouse.simplejs.builtins.object.ObjectDescriptors.getOwnPropertyDescriptors;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.assign;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.entries;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.fromEntries;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.groupBy;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.keys;
import static org.techhouse.simplejs.builtins.object.ObjectEnumeration.values;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.getOwnPropertyNames;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.getOwnPropertySymbols;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.hasOwn;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.setIntegrityLevel;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.testIntegrityLevel;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.OrdinaryProperties;

public final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, InterpreterOps ops, Invoker invoker,
            Intrinsics intrinsics) {
        final var object = new JsNativeFunction("Object", (thisArg, args) -> coerceToObject(thisArg, args, intrinsics));
        object.setProperty("keys", new JsNativeFunction("keys", (_, args) -> keys(boxed(args, intrinsics), ops)));
        object.setProperty("values", new JsNativeFunction("values", (_, args) -> values(boxed(args, intrinsics), ops)));
        object.setProperty("entries",
                new JsNativeFunction("entries", (_, args) -> entries(boxed(args, intrinsics), ops)));
        object.setProperty("assign", new JsNativeFunction("assign", (_, args) -> assign(args, ops, intrinsics)));
        object.setProperty("freeze", new JsNativeFunction("freeze", (_, args) -> setIntegrityLevel(args, ops, true)));
        object.setProperty("isFrozen",
                new JsNativeFunction("isFrozen", (_, args) -> testIntegrityLevel(args, ops, true)));
        object.setProperty("seal", new JsNativeFunction("seal", (_, args) -> setIntegrityLevel(args, ops, false)));
        object.setProperty("isSealed",
                new JsNativeFunction("isSealed", (_, args) -> testIntegrityLevel(args, ops, false)));
        object.setProperty("preventExtensions", new JsNativeFunction("preventExtensions", (_, args) -> {
            if (InterpreterUtils.isObjectLike(first(args)) && !ops.preventExtensions(first(args))) {
                throw new TypeErrorException("Cannot prevent extensions");
            }
            return first(args);
        }));
        object.setProperty("isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(first(args)))));
        object.setProperty("create", new JsNativeFunction("create", (_, args) -> createObject(args, ops, intrinsics)));
        object.setProperty("getPrototypeOf", new JsNativeFunction("getPrototypeOf", (_, args) -> {
            if (InterpreterUtils.isNullish(first(args))) {
                throw new TypeErrorException("Cannot convert undefined or null to object");
            }
            return ops.getPrototypeOf(intrinsics.toObject(first(args)));
        }));
        object.setProperty("setPrototypeOf", new JsNativeFunction("setPrototypeOf", (_, args) -> {
            if (!ops.setPrototypeOf(first(args), argAt(args, 1))) {
                throw new TypeErrorException("Object.setPrototypeOf: trap returned falsish for property '"
                        + JsCoercion.toStr(argAt(args, 1)) + "'");
            }
            return first(args);
        }));
        object.setProperty("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> {
            if (!ops.defineProperty(first(args), argAt(args, 1), argAt(args, 2))) {
                throw new TypeErrorException("Object.defineProperty: trap returned falsish for property '"
                        + JsCoercion.toStr(argAt(args, 1)) + "'");
            }
            return first(args);
        }));
        object.setProperty("defineProperties",
                new JsNativeFunction("defineProperties", (_, args) -> defineProperties(args, ops)));
        object.setProperty("getOwnPropertyNames", new JsNativeFunction("getOwnPropertyNames",
                (_, args) -> getOwnPropertyNames(boxed(args, intrinsics), ops)));
        object.setProperty("getOwnPropertyDescriptor",
                new JsNativeFunction("getOwnPropertyDescriptor",
                        (_, args) -> attachObjectProto(
                                ops.getOwnPropertyDescriptor(intrinsics.toObject(first(args)), argAt(args, 1)),
                                intrinsics)));
        object.setProperty("getOwnPropertyDescriptors", new JsNativeFunction("getOwnPropertyDescriptors",
                (_, args) -> getOwnPropertyDescriptors(boxed(args, intrinsics), ops, intrinsics)));
        object.setProperty("fromEntries",
                new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args, ops, intrinsics)));
        object.setProperty("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args, ops)));
        object.setProperty("groupBy",
                new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker, ops)));
        object.setProperty("is", new JsNativeFunction("is", (_, args) -> is(args)));
        object.setProperty("getOwnPropertySymbols", new JsNativeFunction("getOwnPropertySymbols",
                (_, args) -> getOwnPropertySymbols(boxed(args, intrinsics), ops)));
        return object;
    }

    public static List<JsValue> boxed(List<JsValue> args, Intrinsics intrinsics) {
        final var boxed = new ArrayList<>(args);
        if (boxed.isEmpty()) {
            boxed.add(JsUndefined.getInstance());
        }
        boxed.set(0, intrinsics.toObject(boxed.getFirst()));
        return boxed;
    }

    public static JsValue coerceToObject(JsValue thisArg, List<JsValue> args, Intrinsics intrinsics) {
        if (thisArg instanceof JsObject instance) {
            return instance;
        }
        final var value = first(args);
        if (InterpreterUtils.isNullish(value)) {
            final var created = new JsObject();
            created.setProto(intrinsics.objectProto);
            return created;
        }
        return intrinsics.toObject(value);
    }

    public static JsValue is(List<JsValue> args) {
        return JsBoolean.of(!OrdinaryProperties.isNotSameValue(argAt(args, 0), argAt(args, 1)));
    }

    public static boolean isNotCallable(JsValue value) {
        return !(value instanceof JsFunction) && !(value instanceof JsNativeFunction);
    }

    public static JsValue attachObjectProto(JsValue value, Intrinsics intrinsics) {
        if (value instanceof JsObject object && object.getProto() == null) {
            object.setProto(intrinsics.objectProto);
        }
        return value;
    }

    public static JsValue first(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    public static JsValue argAt(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
