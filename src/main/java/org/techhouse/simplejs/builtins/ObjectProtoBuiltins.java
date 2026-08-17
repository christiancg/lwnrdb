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
            "toString", "toLocaleString", "valueOf", "__defineGetter__", "__defineSetter__", "__lookupGetter__",
            "__lookupSetter__");

    private static final JsString GET = new JsString("get");
    private static final JsString SET = new JsString("set");

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
            case "toLocaleString" -> new JsNativeFunction("toLocaleString", (_, _) -> toLocaleString(receiver, ops));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> requireCoercible(receiver, "valueOf"));
            case "__defineGetter__" -> new JsNativeFunction("__defineGetter__",
                    (_, args) -> defineAccessor(receiver, args, ops, intrinsics, GET));
            case "__defineSetter__" -> new JsNativeFunction("__defineSetter__",
                    (_, args) -> defineAccessor(receiver, args, ops, intrinsics, SET));
            case "__lookupGetter__" -> new JsNativeFunction("__lookupGetter__",
                    (_, args) -> lookupAccessor(receiver, args, ops, intrinsics, GET));
            case "__lookupSetter__" -> new JsNativeFunction("__lookupSetter__",
                    (_, args) -> lookupAccessor(receiver, args, ops, intrinsics, SET));
            default -> null;
        };
    }

    // Object.prototype.__proto__ is an accessor pair rather than a data property, so it is installed
    // on the intrinsic prototype itself instead of being resolved per receiver through getMethod.
    public static void installProtoAccessor(JsObject objectProto, InterpreterOps ops, Intrinsics intrinsics) {
        final var getter = new JsNativeFunction("get __proto__",
                (thisArg, _) -> ops.getPrototypeOf(intrinsics.toObject(thisArg)));
        getter.setLength(0);
        final var setter = new JsNativeFunction("set __proto__", (thisArg, args) -> setProto(thisArg, arg(args), ops));
        setter.setLength(1);
        objectProto.defineAccessor("__proto__", getter, setter);
        objectProto.setFlags("__proto__", new JsObject.PropertyFlags(true, false, true));
    }

    private static JsValue setProto(JsValue receiver, JsValue proto, InterpreterOps ops) {
        requireCoercible(receiver, "__proto__");
        if (!isObjectLike(receiver) || !(isObjectLike(proto) || proto instanceof JsNull)) {
            return JsUndefined.getInstance();
        }
        final var current = ops.getPrototypeOf(receiver);
        if (current == proto || (current instanceof JsNull && proto instanceof JsNull)) {
            return JsUndefined.getInstance();
        }
        // OrdinarySetPrototypeOf returns false for a real change on a non-extensible object, and the
        // Annex B setter turns that false into a TypeError.
        if (!ops.isExtensible(receiver)) {
            throw new TypeErrorException("Cannot set prototype of a non-extensible object");
        }
        ops.setPrototypeOf(receiver, proto);
        return JsUndefined.getInstance();
    }

    private static JsValue toLocaleString(JsValue receiver, InterpreterOps ops) {
        requireCoercible(receiver, "toLocaleString");
        return ops.call(ops.getMember(receiver, new JsString("toString")), receiver, List.of());
    }

    // ToObject runs before ToPropertyKey, so a non-coercible receiver rejects without ever coercing
    // the key.
    private static JsValue defineAccessor(JsValue receiver, List<JsValue> args, InterpreterOps ops,
            Intrinsics intrinsics, JsString side) {
        final var target = intrinsics.toObject(receiver);
        final var accessor = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!isCallable(accessor)) {
            throw new TypeErrorException(
                    "Object.prototype.__define" + (side == GET ? "Getter" : "Setter") + "__: Expecting function");
        }
        final var descriptor = new JsObject();
        descriptor.set(side.getValue(), accessor);
        descriptor.set("enumerable", JsBoolean.TRUE);
        descriptor.set("configurable", JsBoolean.TRUE);
        ops.defineProperty(target, arg(args), descriptor);
        return JsUndefined.getInstance();
    }

    private static JsValue lookupAccessor(JsValue receiver, List<JsValue> args, InterpreterOps ops,
            Intrinsics intrinsics, JsString side) {
        final var target = intrinsics.toObject(receiver);
        final var key = JsCoercion.toPropertyKey(arg(args), ops);
        for (var link = target; isObjectLike(link); link = ops.getPrototypeOf(link)) {
            final var descriptor = ops.getOwnPropertyDescriptor(link, key);
            if (!(descriptor instanceof JsUndefined)) {
                return ops.getMember(descriptor, side);
            }
        }
        return JsUndefined.getInstance();
    }

    private static JsValue arg(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction || value instanceof JsClass;
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
