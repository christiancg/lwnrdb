package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.IteratorBuiltins.iteratorOf;
import static org.techhouse.simplejs.builtins.IteratorBuiltins.lazyIterator;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.values.JsLimits.MAX_ARRAY_LENGTH_DOUBLE;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.IteratorBuiltins.Driver;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class IteratorZip {
    public static JsValue concat(InterpreterOps ops, List<JsValue> items, JsObject objectProto) {
        final var openMethods = new ArrayList<JsValue[]>();
        for (final var item : items) {
            if (!InterpreterUtils.isObjectLike(item)) {
                throw new TypeErrorException("Iterator.concat argument must be an object");
            }
            final var method = ops.getMember(item, JsSymbol.ITERATOR);
            if (!isCallable(method)) {
                throw new TypeErrorException("Iterator.concat argument is not iterable");
            }
            openMethods.add(new JsValue[]{item, method});
        }
        final var index = new int[]{0};
        final var current = new Driver[]{null};
        return lazyIterator(() -> {
            while (true) {
                if (current[0] == null) {
                    if (index[0] >= openMethods.size()) {
                        return null;
                    }
                    final var pair = openMethods.get(index[0]++);
                    current[0] = new Driver(ops, ops.call(pair[1], pair[0], List.of()));
                }
                final var value = current[0].next();
                if (value != null) {
                    return value;
                }
                current[0] = null;
            }
        }, () -> {
            if (current[0] != null) {
                current[0].close();
            }
            index[0] = openMethods.size();
        }, objectProto);
    }

    public static JsValue zip(InterpreterOps ops, JsValue iterablesArg, JsValue optionsArg, JsObject objectProto) {
        if (!InterpreterUtils.isObjectLike(iterablesArg)) {
            throw new TypeErrorException("Iterator.zip argument must be an object");
        }
        final var mode = resolveZipMode(ops, optionsArg);
        final var padding = resolveZipPadding(ops, optionsArg, mode);
        final var entries = new ArrayList<Driver>();
        final var outerMethod = ops.getMember(iterablesArg, JsSymbol.ITERATOR);
        if (!isCallable(outerMethod)) {
            throw new TypeErrorException("Iterator.zip argument must be iterable");
        }
        final var outerDriver = new Driver(ops, ops.call(outerMethod, iterablesArg, List.of()));
        JsValue item;
        while ((item = outerDriver.next()) != null) {
            entries.add(openFlattenable(ops, item, entries));
        }
        final var pads = resolvePads(ops, padding, entries.size());
        return lazyIterator(zipRound(entries, mode, pads, values -> {
            final var array = new JsArray();
            for (final var value : values) {
                array.push(value);
            }
            return array;
        }), () -> entries.forEach(Driver::close), objectProto);
    }

    public static JsValue zipKeyed(InterpreterOps ops, JsValue iterablesArg, JsValue optionsArg, JsObject objectProto) {
        if (!InterpreterUtils.isObjectLike(iterablesArg)) {
            throw new TypeErrorException("Iterator.zipKeyed argument must be an object");
        }
        final var mode = resolveZipMode(ops, optionsArg);
        final var padding = resolveZipPadding(ops, optionsArg, mode);
        final var keys = new ArrayList<JsValue>();
        final var entries = new ArrayList<Driver>();
        for (final var key : ops.ownKeys(iterablesArg)) {
            final var descriptor = ops.getOwnPropertyDescriptor(iterablesArg, key);
            if (!(descriptor instanceof JsObject descObj) || !JsCoercion.toBoolean(descObj.get("enumerable"))) {
                continue;
            }
            final var value = ops.getMember(iterablesArg, key);
            if (value instanceof JsUndefined) {
                continue;
            }
            keys.add(key);
            entries.add(openFlattenable(ops, value, entries));
        }
        final var pads = resolvePads(ops, padding, entries.size());
        return lazyIterator(zipRound(entries, mode, pads, values -> {
            final var obj = new JsObject();
            for (var i = 0; i < keys.size(); i++) {
                if (keys.get(i) instanceof JsSymbol symbol) {
                    obj.setSymbol(symbol, values.get(i));
                } else {
                    obj.set(JsCoercion.toStr(keys.get(i)), values.get(i));
                }
            }
            return obj;
        }), () -> entries.forEach(Driver::close), objectProto);
    }

    public static Supplier<JsValue> zipRound(List<Driver> entries, String mode, JsValue[] pads,
            Function<List<JsValue>, JsValue> build) {
        final var exhausted = new boolean[entries.size()];
        return () -> {
            var allDone = true;
            var anyDone = false;
            final var values = new ArrayList<JsValue>(entries.size());
            for (var i = 0; i < entries.size(); i++) {
                if (exhausted[i]) {
                    anyDone = true;
                    values.add(pads[i]);
                    continue;
                }
                final var value = entries.get(i).next();
                if (value == null) {
                    exhausted[i] = true;
                    anyDone = true;
                    values.add(pads[i]);
                } else {
                    allDone = false;
                    values.add(value);
                }
            }
            if (allDone) {
                return null;
            }
            if (anyDone && !"longest".equals(mode)) {
                entries.forEach(Driver::close);
                if ("strict".equals(mode)) {
                    throw new TypeErrorException("Iterator.zip: iterables of different lengths in strict mode");
                }
                return null;
            }
            return build.apply(values);
        };
    }

    public static Driver openFlattenable(InterpreterOps ops, JsValue item, List<Driver> alreadyOpened) {
        if (!InterpreterUtils.isObjectLike(item)) {
            alreadyOpened.forEach(Driver::close);
            throw new TypeErrorException("Iterator.zip: each iterable must be an object");
        }
        final var method = ops.getMember(item, JsSymbol.ITERATOR);
        final JsValue iterObj;
        if (method instanceof JsUndefined) {
            iterObj = item;
        } else if (isCallable(method)) {
            iterObj = ops.call(method, item, List.of());
            if (!InterpreterUtils.isObjectLike(iterObj)) {
                alreadyOpened.forEach(Driver::close);
                throw new TypeErrorException("Iterator.zip: iterator method did not return an object");
            }
        } else {
            alreadyOpened.forEach(Driver::close);
            throw new TypeErrorException("Iterator.zip: Symbol.iterator is not a function");
        }
        return new Driver(ops, iterObj);
    }

    public static String resolveZipMode(InterpreterOps ops, JsValue optionsArg) {
        final var modeValue = ops.getMember(resolveOptionsObject(optionsArg), new JsString("mode"));
        if (modeValue instanceof JsUndefined) {
            return "shortest";
        }
        if (modeValue instanceof JsString js && Set.of("shortest", "longest", "strict").contains(js.getValue())) {
            return js.getValue();
        }
        throw new TypeErrorException("Invalid Iterator.zip mode");
    }

    public static JsValue resolveZipPadding(InterpreterOps ops, JsValue optionsArg, String mode) {
        if (!"longest".equals(mode)) {
            return JsUndefined.getInstance();
        }
        final var padding = ops.getMember(resolveOptionsObject(optionsArg), new JsString("padding"));
        if (!(padding instanceof JsUndefined) && !InterpreterUtils.isObjectLike(padding)) {
            throw new TypeErrorException("Iterator.zip padding must be an object");
        }
        return padding;
    }

    public static JsValue resolveOptionsObject(JsValue optionsArg) {
        if (optionsArg instanceof JsUndefined) {
            return new JsObject();
        }
        if (InterpreterUtils.isObjectLike(optionsArg)) {
            return optionsArg;
        }
        throw new TypeErrorException("Iterator.zip options must be an object");
    }

    public static JsValue[] resolvePads(InterpreterOps ops, JsValue paddingArg, int count) {
        final var pads = new JsValue[count];
        Arrays.fill(pads, JsUndefined.getInstance());
        if (paddingArg instanceof JsUndefined) {
            return pads;
        }
        final var paddingDriver = new Driver(ops, iteratorOf(ops, paddingArg));
        for (var i = 0; i < count; i++) {
            final var value = paddingDriver.next();
            if (value == null) {
                break;
            }
            pads[i] = value;
        }
        paddingDriver.close();
        return pads;
    }

    public static int windowSize(JsValue value, String label) {
        if (!(value instanceof JsNumber size)) {
            throw new TypeErrorException(label + " must be a Number");
        }
        final var number = size.getValue();
        if (Double.isNaN(number) || number != Math.floor(number) || Double.isInfinite(number)) {
            throw new TypeErrorException(label + " must be an integral Number");
        }
        if (number < 1 || number > MAX_ARRAY_LENGTH_DOUBLE) {
            throw new RangeErrorException(label + " is out of range");
        }
        return (int) Math.min(number, Integer.MAX_VALUE);
    }

    public static boolean allowPartial(List<JsValue> args) {
        final var undersized = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (undersized instanceof JsUndefined || isText(undersized, "only-full")) {
            return false;
        }
        if (isText(undersized, "allow-partial")) {
            return true;
        }
        throw new TypeErrorException("undersized must be \"only-full\" or \"allow-partial\"");
    }

    public static boolean isText(JsValue value, String expected) {
        return value instanceof JsString text && expected.equals(text.getValue());
    }

    public static long skipCount(List<JsValue> args) {
        if (args.size() < 2 || args.get(1) instanceof JsUndefined) {
            return 0;
        }
        if (!(args.get(1) instanceof JsNumber skip)) {
            throw new TypeErrorException("skipCount must be a Number");
        }
        final var number = skip.getValue();
        if (Double.isNaN(number) || (number != Math.floor(number) && !Double.isInfinite(number))) {
            throw new TypeErrorException("skipCount must be an integral Number");
        }
        if (number < 0) {
            throw new RangeErrorException("skipCount is out of range");
        }
        if (!Double.isInfinite(number) && number > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("skipCount is out of range");
        }
        return Double.isInfinite(number) ? Long.MAX_VALUE : (long) number;
    }

    public static JsValue chunks(Driver source, int size, JsObject objectProto) {
        final var buffer = new ArrayList<JsValue>();
        return lazyIterator(() -> {
            for (var value = source.next(); value != null; value = source.next()) {
                buffer.add(value);
                if (buffer.size() == size) {
                    final var chunk = new JsArray(new ArrayList<>(buffer));
                    buffer.clear();
                    return chunk;
                }
            }
            if (buffer.isEmpty()) {
                return null;
            }
            final var tail = new JsArray(new ArrayList<>(buffer));
            buffer.clear();
            return tail;
        }, source::close, objectProto);
    }

    public static JsValue windows(Driver source, int size, boolean allowPartial, JsObject objectProto) {
        final var buffer = new ArrayList<JsValue>();
        final var partialYielded = new boolean[]{false};
        return lazyIterator(() -> {
            for (var value = source.next(); value != null; value = source.next()) {
                if (buffer.size() == size) {
                    buffer.removeFirst();
                }
                buffer.add(value);
                if (buffer.size() == size) {
                    return new JsArray(new ArrayList<>(buffer));
                }
            }
            if (!allowPartial || partialYielded[0] || buffer.isEmpty() || buffer.size() >= size) {
                return null;
            }
            partialYielded[0] = true;
            return new JsArray(new ArrayList<>(buffer));
        }, source::close, objectProto);
    }

    public static boolean includes(Driver source, JsValue searched, long skip) {
        var index = 0L;
        for (var value = source.next(); value != null; value = source.next()) {
            if (index++ < skip) {
                continue;
            }
            if (SameValueZero.equal(value, searched)) {
                source.close();
                return true;
            }
        }
        return false;
    }

    public static String separator(InterpreterOps ops, List<JsValue> args) {
        return args.isEmpty() || args.getFirst() instanceof JsUndefined ? "," : JsCoercion.toStr(args.getFirst(), ops);
    }

    public static JsValue join(InterpreterOps ops, Driver source, String separator) {
        final var result = new StringBuilder();
        var first = true;
        for (var value = source.next(); value != null; value = source.next()) {
            if (!first) {
                result.append(separator);
            }
            first = false;
            if (!InterpreterUtils.isNullish(value)) {
                result.append(coerceOrClose(ops, source, value));
            }
        }
        return new JsString(result.toString());
    }

    public static String coerceOrClose(InterpreterOps ops, Driver source, JsValue value) {
        try {
            return JsCoercion.toStr(value, ops);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    private IteratorZip() {
    }
}
