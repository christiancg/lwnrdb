package org.techhouse.simplejs.builtins.array;

import static org.techhouse.simplejs.builtins.ArrayBuiltins.isArray;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.relativeIndex;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.thisArg;
import static org.techhouse.simplejs.builtins.ArrayBuiltins.toIntegerOrInfinity;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.BuiltinArgs.callback;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.createDataPropertyOrThrow;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.newArray;
import static org.techhouse.simplejs.builtins.array.ArrayConstruct.speciesCreate;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER_LONG;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArraySort {
    public static JsValue sort(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var comparator = comparator(args);
        final var length = target.length();
        final var items = sortIndexedProperties(target, length, false, ops);
        final var sorted = sorted(items, sortCompare(comparator, invoker, ops), ops);
        for (var i = 0; i < sorted.size(); i++) {
            target.set(i, sorted.get(i));
        }
        for (var i = (long) sorted.size(); i < length; i++) {
            target.delete(i);
        }
        return target.value;
    }

    public static JsValue toSorted(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var comparator = comparator(args);
        final var length = target.length();
        final var sorted = sorted(sortIndexedProperties(target, length, true, ops),
                sortCompare(comparator, invoker, ops), ops);
        final var result = newArray(length, ops);
        for (var i = 0; i < sorted.size(); i++) {
            createDataPropertyOrThrow(result, i, sorted.get(i), ops);
        }
        return result;
    }

    public static JsValue comparator(List<JsValue> args) {
        final var comparator = arg(args, 0);
        if (comparator instanceof JsUndefined) {
            return null;
        }
        if (!InterpreterUtils.isCallable(comparator)) {
            throw new TypeErrorException("The comparison function must be either a function or undefined");
        }
        return comparator;
    }

    public static void requireMaterializableLength(long length, InterpreterOps ops) {
        if (length > Integer.MAX_VALUE) {
            throw new RangeErrorException("Invalid array length");
        }
        InterpreterOps.chargeElements(ops, length);
    }

    public static List<JsValue> sortIndexedProperties(ArrayLike target, long length, boolean readThroughHoles,
            InterpreterOps ops) {
        requireMaterializableLength(length, ops);
        final var items = new ArrayList<JsValue>();
        for (var i = 0L; i < length; i++) {
            if (readThroughHoles || target.has(i)) {
                items.add(target.get(i));
            }
        }
        return items;
    }

    public static Comparator<JsValue> sortCompare(JsValue comparator, Invoker invoker, InterpreterOps ops) {
        return (left, right) -> {
            if (left instanceof JsUndefined || right instanceof JsUndefined) {
                if (left instanceof JsUndefined && right instanceof JsUndefined) {
                    return 0;
                }
                return left instanceof JsUndefined ? 1 : -1;
            }
            if (comparator != null) {
                final var result = JsCoercion
                        .toNumber(invoker.call(comparator, JsUndefined.getInstance(), List.of(left, right)), ops);
                if (Double.isNaN(result) || result == 0) {
                    return 0;
                }
                return result < 0 ? -1 : 1;
            }
            return JsCoercion.toStr(left, ops).compareTo(JsCoercion.toStr(right, ops));
        };
    }

    public static List<JsValue> sorted(List<JsValue> items, Comparator<JsValue> comparator, InterpreterOps ops) {
        if (items.size() < 2) {
            return items;
        }
        final var middle = items.size() / 2;
        InterpreterOps.chargeElements(ops, items.size() * 2L);
        final var left = sorted(new ArrayList<>(items.subList(0, middle)), comparator, ops);
        final var right = sorted(new ArrayList<>(items.subList(middle, items.size())), comparator, ops);
        final var merged = new ArrayList<JsValue>(items.size());
        var i = 0;
        var j = 0;
        while (i < left.size() && j < right.size()) {
            if (comparator.compare(right.get(j), left.get(i)) < 0) {
                merged.add(right.get(j++));
            } else {
                merged.add(left.get(i++));
            }
        }
        while (i < left.size()) {
            merged.add(left.get(i++));
        }
        while (j < right.size()) {
            merged.add(right.get(j++));
        }
        return merged;
    }

    public static JsValue flat(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var depth = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? 1
                : toIntegerOrInfinity(args.getFirst(), ops);
        final var result = speciesCreate(target, 0, ops);
        flattenInto(result, target, length, 0, depth, null, null, null, ops);
        return result;
    }

    public static JsValue flatMap(ArrayLike target, List<JsValue> args, Invoker invoker, InterpreterOps ops) {
        final var length = target.length();
        final var callback = callback(args);
        final var self = thisArg(args);
        final var result = speciesCreate(target, 0, ops);
        flattenInto(result, target, length, 0, 1, callback, self, invoker, ops);
        return result;
    }

    public static long flattenInto(JsValue result, ArrayLike source, long length, long start, double depth,
            JsValue mapper, JsValue self, Invoker invoker, InterpreterOps ops) {
        var written = start;
        for (var i = 0L; i < length; i++) {
            if (!source.has(i)) {
                continue;
            }
            var element = source.get(i);
            if (mapper != null) {
                element = invoker.call(mapper, self, List.of(element, new JsNumber(i), source.value));
            }
            if (depth > 0 && isArray(element)) {
                final var nested = new ArrayLike(element, ops);
                written = flattenInto(result, nested, nested.length(), written, depth - 1, null, null, null, ops);
            } else {
                if (written >= MAX_SAFE_INTEGER_LONG) {
                    throw new TypeErrorException("Invalid array length");
                }
                createDataPropertyOrThrow(result, written, element, ops);
                written++;
            }
        }
        return written;
    }

    public static JsValue toReversed(ArrayLike target, InterpreterOps ops) {
        final var length = target.length();
        requireMaterializableLength(length, ops);
        final var result = newArray(length, ops);
        for (var i = 0L; i < length; i++) {
            createDataPropertyOrThrow(result, i, target.get(length - i - 1), ops);
        }
        return result;
    }

    public static JsValue toSpliced(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var start = relativeIndex(toIntegerOrInfinity(arg(args, 0), ops), length);
        final var insertCount = Math.max(args.size() - 2, 0);
        final long skipCount;
        if (args.isEmpty()) {
            skipCount = 0;
        } else if (args.size() == 1) {
            skipCount = length - start;
        } else {
            skipCount = (long) Math.clamp(toIntegerOrInfinity(args.get(1), ops), 0, (double) (length - start));
        }
        final var newLength = length + insertCount - skipCount;
        if (newLength > MAX_SAFE_INTEGER_LONG) {
            throw new TypeErrorException("Invalid array length");
        }
        requireMaterializableLength(newLength, ops);
        final var result = newArray(newLength, ops);
        var written = 0L;
        while (written < start) {
            createDataPropertyOrThrow(result, written, target.get(written), ops);
            written++;
        }
        for (var i = 0; i < insertCount; i++) {
            createDataPropertyOrThrow(result, written, args.get(i + 2), ops);
            written++;
        }
        var read = start + skipCount;
        while (written < newLength) {
            createDataPropertyOrThrow(result, written, target.get(read), ops);
            written++;
            read++;
        }
        return result;
    }

    public static JsValue with(ArrayLike target, List<JsValue> args, InterpreterOps ops) {
        final var length = target.length();
        final var relative = toIntegerOrInfinity(arg(args, 0), ops);
        final var index = relative >= 0 ? relative : length + relative;
        if (index < 0 || index >= length) {
            throw new RangeErrorException("Invalid index : " + relative);
        }
        final var replacement = arg(args, 1);
        requireMaterializableLength(length, ops);
        final var result = newArray(length, ops);
        for (var i = 0L; i < length; i++) {
            createDataPropertyOrThrow(result, i, i == (long) index ? replacement : target.get(i), ops);
        }
        return result;
    }

    private ArraySort() {
    }
}
