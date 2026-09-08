package org.techhouse.simplejs.builtins.array;

import static org.techhouse.simplejs.builtins.ArrayBuiltins.DONE;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.LENGTH;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.NEXT;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.RETURN;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.VALUE;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.isArray;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;
import static org.techhouse.simplejs.values.JsLimits.MAX_ARRAY_LENGTH;
import static org.techhouse.simplejs.values.JsLimits.MAX_ARRAY_LENGTH_DOUBLE;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayConstruct {
    public static JsValue of(JsValue receiver, List<JsValue> args, InterpreterOps ops) {
        final var length = args.size();
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of(new JsNumber(length)))
                : newArray(length, ops);
        for (var i = 0; i < length; i++) {
            createDataPropertyOrThrow(result, i, args.get(i), ops);
        }
        setLengthOrThrow(result, length, ops);
        return result;
    }

    public static JsValue from(JsValue receiver, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var source = arg(args, 0);
        final var mapFn = arg(args, 1);
        if (!(mapFn instanceof JsUndefined) && !InterpreterUtils.isCallable(mapFn)) {
            throw new TypeErrorException("Array.from: when provided, the second argument must be a function");
        }
        final var mapThisArg = arg(args, 2);
        final var iteratorMethod = ops == null ? null : ops.getMember(source, JsSymbol.ITERATOR);
        if (InterpreterUtils.isCallable(iteratorMethod)) {
            return fromIterator(receiver, source, iteratorMethod, mapFn, mapThisArg, invoker, ops);
        }
        return fromArrayLike(receiver, source, mapFn, mapThisArg, invoker, ops);
    }

    public static JsValue fromIterator(JsValue receiver, JsValue source, JsValue iteratorMethod, JsValue mapFn,
            JsValue mapThisArg, Invoker invoker, InterpreterOps ops) {
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of())
                : new JsArray();
        final var iterator = ops.call(iteratorMethod, source, List.of());
        final var next = ops.getMember(iterator, NEXT);
        var written = 0L;
        while (true) {
            final var step = ops.call(next, iterator, List.of());
            if (JsCoercion.toBoolean(ops.getMember(step, DONE))) {
                setLengthOrThrow(result, written, ops);
                return result;
            }
            var element = ops.getMember(step, VALUE);
            try {
                if (!(mapFn instanceof JsUndefined)) {
                    element = invoker.call(mapFn, mapThisArg, List.of(element, new JsNumber(written)));
                }
                createDataPropertyOrThrow(result, written, element, ops);
            } catch (RuntimeException error) {
                closeIterator(iterator, ops, error);
                throw error;
            }
            written++;
        }
    }

    public static JsValue fromArrayLike(JsValue receiver, JsValue source, JsValue mapFn, JsValue mapThisArg,
            Invoker invoker, InterpreterOps ops) {
        final var arrayLike = new ArrayLike(requireObjectSource(source), ops);
        final var length = arrayLike.length();
        InterpreterOps.chargeElements(ops, length);
        final var result = InterpreterUtils.isConstructor(receiver)
                ? ops.construct(receiver, List.of(new JsNumber(length)))
                : newArray(length, ops);
        for (var i = 0L; i < length; i++) {
            InterpreterOps.tick(ops);
            final var element = arrayLike.get(i);
            final var mapped = mapFn instanceof JsUndefined
                    ? element
                    : invoker.call(mapFn, mapThisArg, List.of(element, new JsNumber(i)));
            createDataPropertyOrThrow(result, i, mapped, ops);
        }
        setLengthOrThrow(result, length, ops);
        return result;
    }

    public static JsValue requireObjectSource(JsValue source) {
        if (source instanceof JsNull || source instanceof JsUndefined) {
            throw new TypeErrorException("Array.from requires an array-like or iterable object");
        }
        return source;
    }

    // IteratorClose: the pending error wins, so a throw from the iterator's own `return` is dropped.
    static void closeIterator(JsValue iterator, InterpreterOps ops, RuntimeException pending) {
        if (pending instanceof ScriptAbortException) {
            return;
        }
        try {
            final var close = ops.getMember(iterator, RETURN);
            if (InterpreterUtils.isCallable(close)) {
                ops.call(close, iterator, List.of());
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void setLengthOrThrow(JsValue target, long length, InterpreterOps ops) {
        if (target instanceof JsArray array) {
            if (!array.setLength(length)) {
                throw new TypeErrorException("Cannot assign to read only property 'length' of object");
            }
            return;
        }
        if (ops != null && !ops.setMember(target, LENGTH, new JsNumber(length))) {
            throw new TypeErrorException("Cannot assign to read only property 'length' of object");
        }
    }

    public static void createDataPropertyOrThrow(JsValue target, long index, JsValue value, InterpreterOps ops) {
        if (target instanceof JsArray array) {
            if (index > Integer.MAX_VALUE || !array.set((int) index, value)) {
                throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
            }
            return;
        }
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(true));
        descriptor.set("configurable", JsBoolean.of(true));
        if (ops == null || !ops.defineProperty(target, new JsString(Long.toString(index)), descriptor)) {
            throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
        }
    }

    public static JsValue construct(JsValue thisArg, List<JsValue> args, InterpreterOps ops) {
        final JsValue constructed;
        if (args.size() == 1 && args.getFirst() instanceof JsNumber number) {
            final var length = number.getValue();
            if (length < 0 || length != Math.floor(length) || length > MAX_ARRAY_LENGTH_DOUBLE) {
                throw new RangeErrorException("Invalid array length");
            }
            constructed = newArray((long) length, ops);
        } else {
            constructed = new JsArray(new ArrayList<>(args));
        }
        return thisArg instanceof JsUndefined ? withNewTargetPrototype(constructed, ops) : constructed;
    }

    public static JsArray newArray(long length, InterpreterOps ops) {
        if (length > MAX_ARRAY_LENGTH) {
            throw new RangeErrorException("Invalid array length");
        }
        chargeDenseGrowth(length, ops);
        final var result = new JsArray();
        result.setLength(length);
        return result;
    }

    public static void chargeDenseGrowth(long length, InterpreterOps ops) {
        InterpreterOps.chargeElements(ops, length <= JsArray.MAX_DENSE_LENGTH ? length : 0);
    }

    public static JsValue speciesCreate(ArrayLike target, long length, InterpreterOps ops) {
        if (ops == null || !isArray(target.value())) {
            return newArray(length, ops);
        }
        var constructor = ops.getMember(target.value(), new JsString("constructor"));
        if (InterpreterUtils.isObjectLike(constructor)) {
            constructor = ops.getMember(constructor, JsSymbol.SPECIES);
            if (constructor instanceof JsNull) {
                constructor = JsUndefined.getInstance();
            }
        }
        if (constructor instanceof JsUndefined) {
            return newArray(length, ops);
        }
        if (!InterpreterUtils.isConstructor(constructor)) {
            throw new TypeErrorException("The constructor property is not a constructor");
        }
        return ops.construct(constructor, List.of(new JsNumber(length)));
    }

    public static void setResultLength(JsValue result, long length, InterpreterOps ops) {
        if (result instanceof JsArray array) {
            chargeDenseGrowth(length, ops);
            array.setLength(length);
        } else if (ops != null) {
            ops.setMember(result, LENGTH, new JsNumber(length));
        }
    }

    private ArrayConstruct() {
    }
}
