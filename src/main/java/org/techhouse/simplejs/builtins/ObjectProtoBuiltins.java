package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectProtoBuiltins {
    public static final List<String> NAMES = List.of("hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable",
            "toString", "valueOf");

    private ObjectProtoBuiltins() {
    }

    public static JsNativeFunction getMethod(JsValue receiver, String name, InterpreterOps ops, Intrinsics intrinsics) {
        return switch (name) {
            case "hasOwnProperty" ->
                new JsNativeFunction("hasOwnProperty", (_, args) -> JsBoolean.of(hasOwnProperty(receiver, args)));
            case "isPrototypeOf" -> new JsNativeFunction("isPrototypeOf",
                    (_, args) -> JsBoolean.of(isPrototypeOf(receiver, args, intrinsics)));
            case "propertyIsEnumerable" ->
                new JsNativeFunction("propertyIsEnumerable", (_, args) -> JsBoolean.of(isEnumerable(receiver, args)));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(objectToString(receiver, ops)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> requireCoercible(receiver, "valueOf"));
            default -> null;
        };
    }

    private static String objectToString(JsValue receiver, InterpreterOps ops) {
        if (ops != null && !(receiver instanceof JsUndefined) && !(receiver instanceof JsNull)
                && ops.getMember(receiver, JsSymbol.TO_STRING_TAG) instanceof JsString tag) {
            return "[object " + tag.getValue() + "]";
        }
        return "[object " + brand(receiver) + "]";
    }

    private static String brand(JsValue receiver) {
        return switch (receiver) {
            case JsUndefined ignored -> "Undefined";
            case JsNull ignored -> "Null";
            case JsArray ignored -> "Array";
            case JsArguments ignored -> "Arguments";
            case JsFunction ignored -> "Function";
            case JsNativeFunction ignored -> "Function";
            case JsClass ignored -> "Function";
            case JsBoolean ignored -> "Boolean";
            case JsNumber ignored -> "Number";
            case JsBigInt ignored -> "BigInt";
            case JsString ignored -> "String";
            case JsSymbol ignored -> "Symbol";
            case JsDate ignored -> "Date";
            case JsRegExp ignored -> "RegExp";
            case JsMap map -> map.isWeak() ? "WeakMap" : "Map";
            case JsSet set -> set.isWeak() ? "WeakSet" : "Set";
            case JsPromise ignored -> "Promise";
            case JsGenerator ignored -> "Generator";
            case JsAsyncGenerator ignored -> "AsyncGenerator";
            case JsArrayBuffer ignored -> "ArrayBuffer";
            case JsDataView ignored -> "DataView";
            case JsTypedArray typed -> typed.kind().ctorName();
            case JsGlobalObject ignored -> "global";
            case JsProxy proxy -> brand(proxy.getTarget());
            case JsObject object -> wrapperBrand(object);
            default -> "Object";
        };
    }

    private static String wrapperBrand(JsObject object) {
        if (object.isErrorData()) {
            return "Error";
        }
        return switch (object.getPrimitive()) {
            case JsNumber ignored -> "Number";
            case JsString ignored -> "String";
            case JsBoolean ignored -> "Boolean";
            case null, default -> "Object";
        };
    }

    private static boolean hasOwnProperty(JsValue receiver, List<JsValue> args) {
        requireCoercible(receiver, "hasOwnProperty");
        if (args.isEmpty()) {
            return false;
        }
        if (args.getFirst() instanceof JsSymbol symbol) {
            return ObjectBuiltins.hasOwnSymbol(receiver, symbol);
        }
        return ObjectBuiltins.hasOwnKey(receiver, JsCoercion.toStr(args.getFirst()));
    }

    private static boolean isEnumerable(JsValue receiver, List<JsValue> args) {
        requireCoercible(receiver, "propertyIsEnumerable");
        if (args.isEmpty()) {
            return false;
        }
        if (args.getFirst() instanceof JsSymbol symbol) {
            return ObjectBuiltins.hasOwnSymbol(receiver, symbol);
        }
        final var key = JsCoercion.toStr(args.getFirst());
        if (!ObjectBuiltins.hasOwnKey(receiver, key)) {
            return false;
        }
        return switch (receiver) {
            case JsObject object -> object.isEnumerable(key);
            case JsClass cls -> cls.getStaticOwner().isEnumerable(key);
            case JsCallableProperties callable -> callable.enumerablePropertyKeys().contains(key);
            default -> !"length".equals(key);
        };
    }

    // A value's builtin prototype is reached through Intrinsics.protoFor rather than a proto link, and
    // those prototypes terminate at Object.prototype implicitly, so both hops are walked explicitly.
    private static boolean isPrototypeOf(JsValue receiver, List<JsValue> args, Intrinsics intrinsics) {
        if (args.isEmpty() || !isObjectLike(receiver) || !isObjectLike(args.getFirst())) {
            return false;
        }
        final var candidate = args.getFirst();
        var proto = candidate.getProto();
        if (proto == null && intrinsics != null) {
            proto = intrinsics.protoFor(candidate);
        }
        while (proto != null) {
            if (proto == receiver) {
                return true;
            }
            final var next = proto.getProto();
            proto = next == null && intrinsics != null && proto != intrinsics.objectProto()
                    ? intrinsics.objectProto()
                    : next;
        }
        return false;
    }

    private static JsValue requireCoercible(JsValue receiver, String method) {
        if (receiver instanceof JsUndefined || receiver instanceof JsNull) {
            throw new TypeErrorException("Object.prototype." + method + " called on null or undefined");
        }
        return receiver;
    }
}
