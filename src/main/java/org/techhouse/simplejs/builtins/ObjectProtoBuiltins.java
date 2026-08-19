package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
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
                new JsNativeFunction("hasOwnProperty", (_, args) -> JsBoolean.of(hasOwnProperty(receiver, args, ops)));
            case "isPrototypeOf" -> new JsNativeFunction("isPrototypeOf",
                    (_, args) -> JsBoolean.of(isPrototypeOf(receiver, args, ops, intrinsics)));
            case "propertyIsEnumerable" -> new JsNativeFunction("propertyIsEnumerable",
                    (_, args) -> JsBoolean.of(isEnumerable(receiver, args, ops)));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(objectToString(receiver, ops)));
            case "toLocaleString" -> new JsNativeFunction("toLocaleString", (_, _) -> toLocaleString(receiver, ops));
            // Object.prototype.valueOf: 1. Return ? ToObject(this value) - a primitive receiver must
            // come back as its wrapper object, not the bare primitive.
            case "valueOf" ->
                new JsNativeFunction("valueOf", (_, _) -> intrinsics.toObject(requireCoercible(receiver, "valueOf")));
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
        // OrdinarySetPrototypeOf can also answer false for a cycle or an immutable-prototype exotic
        // object (%Object.prototype%) - both real rejections the Annex B setter must surface as a
        // TypeError too, not just the extensibility case checked above.
        if (!ops.setPrototypeOf(receiver, proto)) {
            throw new TypeErrorException("Cannot set prototype: rejected for '" + JsCoercion.toStr(proto) + "'");
        }
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

    // ES2026 step 14's builtinTag switch names only Array/Function/Error/Boolean/Number/String/
    // Date/RegExp (else "Object"); a Map/Set/WeakMap/WeakSet/Promise/Generator/AsyncGenerator/
    // Symbol/BigInt object's usual type name comes entirely from a *real*, deletable/overridable
    // @@toStringTag property installed on its prototype (consulted above, before brand() runs) -
    // never from this builtin-tag fallback, so once that property is removed or answers a non-string
    // the fallback here must be "Object", not the type name.
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
            case JsString ignored -> "String";
            case JsDate ignored -> "Date";
            case JsRegExp ignored -> "RegExp";
            case JsArrayBuffer ignored -> "ArrayBuffer";
            case JsDataView ignored -> "DataView";
            case JsTypedArray typed -> typed.kind().ctorName();
            case JsGlobalObject ignored -> "global";
            case JsProxy proxy -> proxyBrand(proxy);
            case JsObject object -> wrapperBrand(object);
            default -> "Object";
        };
    }

    // Per spec, only two of step 14's builtin-tag checks are themselves proxy-transparent: IsArray
    // (7.2.2 explicitly recurses into [[ProxyTarget]]) and [[Call]] presence (a Proxy exotic object
    // literally has its own [[Call]] internal method whenever its target is callable - not an
    // "unwrap", a real own internal method). Every other check (Boolean/Number/String/Date/RegExp/
    // ErrorData) is a genuine internal slot the Proxy object itself never has, regardless of its
    // target - so `new Proxy(new Date, {})` must answer "Object", not "Date" (test262 built-ins/
    // Object/prototype/toString/non-callable-join-string-tag.js). A prior version of this method
    // unwrapped to the target's own brand unconditionally, which happened to keep the Array/Function
    // cases correct while silently reflecting every other internal-slot type through the proxy too.
    private static String proxyBrand(JsProxy proxy) {
        final var target = proxy.getTarget();
        if (proxyResolvesToArray(target)) {
            return "Array";
        }
        if (proxyResolvesToCallable(target)) {
            return "Function";
        }
        return "Object";
    }

    private static boolean proxyResolvesToArray(JsValue value) {
        return switch (value) {
            case JsArray ignored -> true;
            case JsProxy proxy -> proxyResolvesToArray(proxy.getTarget());
            default -> false;
        };
    }

    private static boolean proxyResolvesToCallable(JsValue value) {
        return switch (value) {
            case JsFunction ignored -> true;
            case JsNativeFunction ignored -> true;
            case JsClass ignored -> true;
            case JsProxy proxy -> proxyResolvesToCallable(proxy.getTarget());
            default -> false;
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

    // ToPropertyKey(V) runs before ToObject(this value), so a poisoned key coercion is observed even
    // when the receiver is null - and a wrapper whose @@toPrimitive yields a symbol keys by symbol.
    private static boolean hasOwnProperty(JsValue receiver, List<JsValue> args, InterpreterOps ops) {
        final var key = JsCoercion.toPropertyKey(arg(args), ops);
        requireCoercible(receiver, "hasOwnProperty");
        if (key instanceof JsSymbol symbol) {
            return ObjectBuiltins.hasOwnSymbol(receiver, symbol, ops);
        }
        return ObjectBuiltins.hasOwnKey(receiver, JsCoercion.toStr(key), ops);
    }

    private static boolean isEnumerable(JsValue receiver, List<JsValue> args, InterpreterOps ops) {
        final var propertyKey = JsCoercion.toPropertyKey(arg(args), ops);
        requireCoercible(receiver, "propertyIsEnumerable");
        if (propertyKey instanceof JsSymbol symbol) {
            return ObjectBuiltins.hasOwnSymbol(receiver, symbol, ops)
                    && ObjectBuiltins.isEnumerableOwnSymbol(receiver, symbol);
        }
        final var key = JsCoercion.toStr(propertyKey);
        if (!ObjectBuiltins.hasOwnKey(receiver, key, ops)) {
            return false;
        }
        return switch (receiver) {
            case JsObject object -> object.isEnumerable(key);
            case JsClass cls -> cls.getStaticOwner().isEnumerable(key);
            case JsCallableProperties callable -> callable.enumerablePropertyKeys().contains(key);
            case JsGlobalObject global -> {
                final var descriptor = global.getOwnProperty(new JsString(key));
                yield descriptor != null && Boolean.TRUE.equals(descriptor.enumerable());
            }
            default -> !"length".equals(key);
        };
    }

    // Object.prototype.isPrototypeOf(V): 1. If V is not an Object, return false. 2. Let O be
    // ? ToObject(this value). - step 1 must run (and short-circuit to false) before step 2 even
    // looks at `this`, so a nullish/primitive receiver only throws/no-ops once V is confirmed to be
    // an object. A value's builtin prototype is reached through Intrinsics.protoFor rather than a
    // proto link, and those prototypes terminate at Object.prototype implicitly, so both hops are
    // walked explicitly; a Proxy anywhere in the chain runs its "getPrototypeOf" trap via the ops
    // seam instead of a raw (and for a Proxy, absent) JsValue.getProto() read.
    private static boolean isPrototypeOf(JsValue receiver, List<JsValue> args, InterpreterOps ops,
            Intrinsics intrinsics) {
        final var candidate = arg(args);
        if (!isObjectLike(candidate)) {
            return false;
        }
        requireCoercible(receiver, "isPrototypeOf");
        if (!isObjectLike(receiver)) {
            return false;
        }
        var proto = nextProto(candidate, ops, intrinsics);
        while (proto != null) {
            if (proto == receiver) {
                return true;
            }
            proto = nextProto(proto, ops, intrinsics);
        }
        return false;
    }

    private static JsValue nextProto(JsValue value, InterpreterOps ops, Intrinsics intrinsics) {
        if (value instanceof JsProxy) {
            final var proto = ops.getPrototypeOf(value);
            return proto instanceof JsNull ? null : proto;
        }
        final var proto = value.getProto();
        if (proto != null) {
            return proto;
        }
        if (intrinsics == null || value == intrinsics.objectProto()) {
            return null;
        }
        return intrinsics.protoFor(value);
    }

    private static JsValue requireCoercible(JsValue receiver, String method) {
        if (receiver instanceof JsUndefined || receiver instanceof JsNull) {
            throw new TypeErrorException("Object.prototype." + method + " called on null or undefined");
        }
        return receiver;
    }
}
