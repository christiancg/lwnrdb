package org.techhouse.simplejs.builtins.intrinsics;

import static org.techhouse.simplejs.builtins.Intrinsics.ERROR_NAMES;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBoolean;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireBrand;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireObject;

import java.util.List;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.BuiltinLengths;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.builtins.regex.RegexSymbolMethods;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record IntrinsicsPrototypes(Intrinsics intrinsics) {
    public JsObject functionKindPrototype(String name, JsObject instancePrototype) {
        final var proto = new JsObject();
        proto.setProto(intrinsics.functionProto);
        final var ctor = new JsNativeFunction(name, (_, _) -> {
            throw new TypeErrorException(name + " is not supported: SimpleJS has no runtime code generation");
        });
        ctor.setLength(1);
        ctor.markConstructor();
        ctor.setPrototype(proto);
        intrinsics.functionKindCtors.put(name, ctor);
        Intrinsics.define(proto, "constructor", ctor);
        proto.setFlags("constructor", PropertyFlags.TAG);
        if (instancePrototype != null) {
            Intrinsics.define(proto, "prototype", instancePrototype);
            proto.setFlags("prototype", PropertyFlags.TAG);
            Intrinsics.define(instancePrototype, "constructor", proto);
            instancePrototype.setFlags("constructor", PropertyFlags.TAG);
        }
        IntrinsicsInstallers.defineToStringTag(proto, name);
        return proto;
    }

    public void linkFunctionKindConstructors(JsValue functionConstructor) {
        for (final var ctor : intrinsics.functionKindCtors.values()) {
            ctor.setOwnProto(functionConstructor);
        }
    }

    public static JsNativeFunction makeThrowTypeError() {
        final var fn = new JsNativeFunction("", (_, _) -> {
            throw new TypeErrorException(
                    "'caller', 'callee', and 'arguments' properties may not be accessed on strict mode functions");
        });
        fn.setLength(0);
        final var table = fn.ownProperties();
        table.defineValue("length", new JsNumber(0));
        table.setFlags("length", new PropertyFlags(false, false, false));
        table.defineValue("name", new JsString(""));
        table.setFlags("name", new PropertyFlags(false, false, false));
        table.preventExtensions();
        return fn;
    }

    public void installPoisonPill(JsObject target, String key) {
        target.defineAccessor(key, intrinsics.throwTypeError, intrinsics.throwTypeError);
        target.setFlags(key, PropertyFlags.TAG);
    }

    public void poison(JsValue target, String key) {
        final var table = target.ownProperties();
        if (table != null) {
            table.defineAccessor(key, intrinsics.throwTypeError, intrinsics.throwTypeError);
            table.setFlags(key, PropertyFlags.TAG);
        }
    }

    public JsObject regexpStringIteratorPrototype() {
        final var proto = new JsObject();
        proto.setProto(intrinsics.objectProto);
        final var next = new JsNativeFunction("next",
                (thisArg, _) -> RegexSymbolMethods.stringIteratorNext(thisArg, intrinsics.ops));
        next.setLength(0);
        Intrinsics.define(proto, "next", next);
        IntrinsicsInstallers.defineToStringTag(proto, "RegExp String Iterator");
        return proto;
    }

    public void linkIteratorPrototypes(JsValue iteratorPrototype, JsValue asyncIteratorPrototype) {
        if (iteratorPrototype != null) {
            iteratorPrototype.setProto(intrinsics.objectProto);
            intrinsics.iteratorProto.setProto(iteratorPrototype);
            intrinsics.regexpStringIteratorProto.setProto(iteratorPrototype);
            intrinsics.arrayIteratorProto.setProto(iteratorPrototype);
            intrinsics.stringIteratorProto.setProto(iteratorPrototype);
            intrinsics.mapIteratorProto.setProto(iteratorPrototype);
            intrinsics.setIteratorProto.setProto(iteratorPrototype);
            intrinsics.dbCursorProto.setProto(iteratorPrototype);
        }
        if (asyncIteratorPrototype != null) {
            asyncIteratorPrototype.setProto(intrinsics.objectProto);
            intrinsics.asyncIteratorProto.setProto(asyncIteratorPrototype);
        }
    }

    public void installObjectPrototype() {
        for (final var name : ObjectProtoBuiltins.NAMES) {
            Intrinsics.define(intrinsics.objectProto, name, intrinsics.wrapper(name, "Object.prototype",
                    (receiver, key) -> ObjectProtoBuiltins.getMethod(receiver, key, intrinsics.ops, intrinsics)));
        }
        ObjectProtoBuiltins.installProtoAccessor(intrinsics.objectProto, intrinsics.ops, intrinsics);
        intrinsics.errorProto.setProto(intrinsics.objectProto);
    }

    public void installErrorPrototypes() {
        Intrinsics.define(intrinsics.errorProto, "toString",
                new JsNativeFunction("toString", (thisArg, _) -> new JsString(errorText(requireObject(thisArg)))));
        ErrorBuiltins.installStackAccessor(intrinsics.errorProto, intrinsics.ops);
        Intrinsics.define(intrinsics.errorProto, "name", new JsString("Error"));
        Intrinsics.define(intrinsics.errorProto, "message", new JsString(""));
        intrinsics.errorProtos.put("Error", intrinsics.errorProto);
        for (final var name : ERROR_NAMES) {
            if (intrinsics.errorProtos.containsKey(name)) {
                continue;
            }
            final var proto = new JsObject();
            proto.setProto(intrinsics.errorProto);
            Intrinsics.define(proto, "name", new JsString(name));
            Intrinsics.define(proto, "message", new JsString(""));
            intrinsics.errorProtos.put(name, proto);
        }
    }

    public String errorText(JsObject error) {
        final var name = errorField(error, "name", "Error");
        final var message = errorField(error, "message", "");
        if (name.isEmpty()) {
            return message;
        }
        return message.isEmpty() ? name : name + ": " + message;
    }

    public String errorField(JsObject error, String key, String fallback) {
        final var value = intrinsics.ops == null ? error.get(key) : intrinsics.ops.getMember(error, new JsString(key));
        if (value == null || value instanceof JsUndefined) {
            return fallback;
        }
        return JsCoercion.toStr(value, intrinsics.ops);
    }

    public void installStringPrimitiveMethods(JsObject proto) {
        for (final var name : List.of("toString", "valueOf")) {
            final var method = new JsNativeFunction(name, (thisArg, _) -> requireStringData(thisArg, name));
            method.setLength(0);
            Intrinsics.define(proto, name, method);
        }
    }

    public static JsString requireStringData(JsValue receiver, String method) {
        return requireBrand(receiver, JsString.class, "String.prototype", method);
    }

    public JsObject booleanPrototype() {
        final var proto = new JsObject();
        Intrinsics.define(proto, "toString", new JsNativeFunction("toString",
                (thisArg, _) -> new JsString(JsCoercion.toStr(requireBoolean(thisArg, "toString")))));
        Intrinsics.define(proto, "valueOf",
                new JsNativeFunction("valueOf", (thisArg, _) -> requireBoolean(thisArg, "valueOf")));
        proto.setProto(intrinsics.objectProto);
        return proto;
    }

    public JsObject arrayPrototype() {
        final var proto = new JsObject();
        for (final var name : ArrayBuiltins.NAMES) {
            final var method = new JsNativeFunction(name,
                    (thisArg, args) -> intrinsics.callArrayMethod(thisArg, name, args));
            method.setLength(BuiltinLengths.lengthOf("Array.prototype", name));
            Intrinsics.define(proto, name, method);
        }
        proto.defineValue("length", new JsNumber(0));
        proto.setFlags("length", new PropertyFlags(true, false, false));
        proto.setProto(intrinsics.objectProto);
        return proto;
    }

    public JsObject builtinIteratorProto(String label, String name) {
        if (!"keys".equals(name) && !"values".equals(name) && !"entries".equals(name)) {
            return null;
        }
        return switch (label) {
            case "Map.prototype", "WeakMap.prototype" -> intrinsics.mapIteratorProto;
            case "Set.prototype", "WeakSet.prototype" -> intrinsics.setIteratorProto;
            case "Array.prototype", "TypedArray.prototype" -> intrinsics.arrayIteratorProto;
            default -> null;
        };
    }
}
