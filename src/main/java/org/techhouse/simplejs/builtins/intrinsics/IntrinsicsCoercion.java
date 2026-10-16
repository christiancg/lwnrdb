package org.techhouse.simplejs.builtins.intrinsics;

import java.util.List;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.BuiltinLengths;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.Intrinsics.MethodResolver;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record IntrinsicsCoercion(Intrinsics intrinsics) {
    private static final String FUNCTION_PROTOTYPE = "Function.prototype";

    public JsValue callArrayMethod(JsValue thisArg, String name, List<JsValue> args) {
        if (thisArg instanceof JsNull || thisArg instanceof JsUndefined) {
            throw IntrinsicsBrands.incompatible("Array.prototype." + name, thisArg);
        }
        final var receiver = toObject(thisArg);
        final var resolved = ArrayBuiltins.getMethod(receiver, name, intrinsics.invoker, intrinsics.ops);
        if (resolved == null) {
            throw IntrinsicsBrands.incompatible("Array.prototype." + name, thisArg);
        }
        return JsIterators.linkPrototype(intrinsics.invoker.call(resolved, receiver, args),
                intrinsics.prototypes.builtinIteratorProto("Array.prototype", name));
    }

    public JsValue toObject(JsValue value) {
        if (InterpreterUtils.isObjectLike(value)) {
            return value;
        }
        if (value instanceof JsNull || value instanceof JsUndefined) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        return wrapPrimitive(value, intrinsics.protoFor(value));
    }

    public JsObject wrapPrimitive(JsValue primitive, JsValue proto) {
        final var wrapper = new JsObject();
        wrapper.setProto(proto);
        wrapper.setPrimitive(primitive);
        return wrapper;
    }

    public JsObject prototypeOf(List<String> names, String label, MethodResolver resolver) {
        final var proto = new JsObject();
        for (final var name : names) {
            Intrinsics.define(proto, name, wrapper(name, label, resolver));
        }
        proto.setProto(intrinsics.objectProto);
        return proto;
    }

    public JsNativeFunction wrapper(String name, String label, MethodResolver resolver) {
        final var wrapped = FUNCTION_PROTOTYPE.equals(label)
                ? JsNativeFunction.callerAware(name,
                        (thisArg, args, caller) -> callResolved(name, label, resolver, thisArg, args, caller))
                : new JsNativeFunction(name,
                        (thisArg, args) -> callResolved(name, label, resolver, thisArg, args, null));
        wrapped.setLength(BuiltinLengths.lengthOf(label, name));
        return wrapped;
    }

    private JsValue callResolved(String name, String label, MethodResolver resolver, JsValue thisArg,
            List<JsValue> args, Coroutine caller) {
        final var method = resolver.resolve(thisArg, name);
        if (method == null) {
            throw IntrinsicsBrands.incompatible(label + "." + name, thisArg);
        }
        final var result = caller == null || intrinsics.ops == null
                ? intrinsics.invoker.call(method, thisArg, args)
                : intrinsics.ops.callFrom(method, thisArg, args, caller);
        return JsIterators.linkPrototype(result, intrinsics.prototypes.builtinIteratorProto(label, name));
    }
}
