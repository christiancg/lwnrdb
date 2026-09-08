package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record ConstructEvaluator(Interpreter interp) {
    public JsValue evalNew(NewExpression expression, Environment env) {
        final var callee = interp.eval(expression.getCallee(), env);
        final var args = interp.evalArguments(expression.getArguments(), env);
        return constructValue(callee, args);
    }

    public JsValue constructValue(JsValue callee, List<JsValue> args) {
        return constructValue(callee, args, callee);
    }

    public JsValue construct(JsValue callee, List<JsValue> args, JsValue newTarget) {
        return constructValue(callee, args, newTarget);
    }

    public JsValue constructValue(JsValue callee, List<JsValue> args, JsValue newTarget) {
        if (!isConstructor(callee)) {
            throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
        }
        return switch (callee) {
            case JsProxy proxy -> interp.proxies.construct(proxy, args, newTarget);
            case JsClass cls -> interp.classes.construct(cls, args, newTarget);
            case JsNativeFunction nativeFunction when nativeFunction.isBound() ->
                constructValue(nativeFunction.getBoundTarget(), boundArgs(nativeFunction, args),
                        newTarget == callee ? nativeFunction.getBoundTarget() : newTarget);
            case JsNativeFunction nativeFunction -> constructNative(nativeFunction, args, newTarget);
            case JsFunction function -> constructFunction(function, args, newTarget);
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
        };
    }

    public JsValue constructNative(JsNativeFunction nativeFunction, List<JsValue> args, JsValue newTarget) {
        final var proto = nativeFunction.getPrototype();
        if (proto == interp.intrinsics.objectProto) {
            if (newTarget != nativeFunction) {
                final var created = new JsObject();
                created.setProto(protoFromNewTarget(newTarget, proto));
                return created;
            }
            final var argument = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (isNullish(argument)) {
                final var created = new JsObject();
                created.setProto(proto);
                return created;
            }
            return interp.intrinsics.toObject(argument);
        }
        if (proto == interp.intrinsics.stringProto || proto == interp.intrinsics.numberProto
                || proto == interp.intrinsics.booleanProto) {
            if (proto == interp.intrinsics.stringProto && !args.isEmpty() && args.getFirst() instanceof JsSymbol) {
                throw new TypeErrorException("Cannot convert a Symbol value to a string");
            }
            return interp.intrinsics.wrapPrimitive(nativeFunction.invoke(JsUndefined.getInstance(), args),
                    protoFromNewTarget(newTarget, proto));
        }
        return nativeFunction.invoke(JsUndefined.getInstance(), args, newTarget);
    }

    public JsValue protoFromNewTarget(JsValue newTarget, JsValue fallback) {
        if (newTarget == null || isNullish(newTarget)) {
            return fallback;
        }
        final var proto = interp.getMemberByKey(newTarget, new JsString("prototype"));
        if (isObjectLike(proto)) {
            return proto;
        }
        if (newTarget instanceof JsProxy proxy && proxy.isRevoked()) {
            throw new TypeErrorException("Cannot perform 'get' on a proxy that has been revoked");
        }
        return fallback;
    }

    public List<JsValue> boundArgs(JsNativeFunction nativeFunction, List<JsValue> args) {
        final var combined = new ArrayList<>(nativeFunction.getBoundArgs());
        combined.addAll(args);
        return combined;
    }

    public JsValue constructFunction(JsFunction function, List<JsValue> args, JsValue newTarget) {
        final var instance = new JsObject();
        instance.setProto(protoFromNewTarget(newTarget, interp.intrinsics.objectProto));
        final var result = interp.callFunction(function, instance, args, newTarget);
        return isObjectLike(result) ? result : instance;
    }

}
